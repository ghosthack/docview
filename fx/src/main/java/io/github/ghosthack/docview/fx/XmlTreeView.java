package io.github.ghosthack.docview.fx;

import io.github.ghosthack.docview.ReaderStyle;
import io.github.ghosthack.docview.XmlTree;
import io.github.ghosthack.docview.XmlTree.Entry;
import io.github.ghosthack.docview.XmlTree.Kind;
import io.github.ghosthack.docview.XmlTree.XmlNode;

import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native XML tree Reader: a virtualized {@link TreeTableView} of an
 * {@link XmlTree} with the WebView XML tree's "Node tag" / "Node value"
 * columns, leaf contraction into key/value pills (globally and per branch),
 * vertical or horizontal pills, and its colors. Tree items are created
 * lazily as branches open, so large documents open quickly. Expansion and
 * scroll position are kept per document.
 */
public final class XmlTreeView {

    private static final int REMEMBERED_DOCUMENTS = 8;

    private final BorderPane root = new BorderPane();
    private final TreeTableView<XmlNode> table = new TreeTableView<>();
    private ReaderTheme theme = ReaderTheme.dark();
    private final TreeTableColumn<XmlNode, XmlNode> tagColumn = new TreeTableColumn<>("NODE TAG");
    private final TreeTableColumn<XmlNode, XmlNode> valueColumn =
            new TreeTableColumn<>("NODE VALUE");
    private final ToggleButton contractLeaves = new ToggleButton("Restore leaves");
    private final ToggleButton verticalPills = new ToggleButton("Horizontal pills");
    private KeyCombination copyKey =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private KeyCombination selectAllKey =
            new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);
    /** Per-document tree items (expansion state) and scroll row, LRU. */
    private final Map<Path, Remembered> remembered =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, Remembered> eldest) {
                    return size() > REMEMBERED_DOCUMENTS;
                }
            };
    private Path path;
    private XmlTree.Tree tree;
    /** Source text of a document showing the parse-error panel, else null. */
    private String errorText;
    private Font code;
    private Font codeBold;
    private double em = 15;
    private Runnable onDoubleClick;

    private record Remembered(XmlTree.Tree tree, XmlItem root, int firstRow) {
    }

    public XmlTreeView() {
        root.getStyleClass().add("docview-xml-tree");
        applyThemeStyle();
        var css = XmlTreeView.class.getResource("xml-tree-view.css");
        if (css != null) root.getStylesheets().add(css.toExternalForm());

        contractLeaves.setSelected(true);
        verticalPills.setSelected(true);
        contractLeaves.setFocusTraversable(false);
        verticalPills.setFocusTraversable(false);
        contractLeaves.setTooltip(new Tooltip("Contract leaf nodes globally"));
        verticalPills.setTooltip(new Tooltip("Use vertical key-value pills"));
        contractLeaves.selectedProperty().addListener((o, was, on) -> {
            contractLeaves.setText(on ? "Restore leaves" : "Contract leaves");
            if (table.getRoot() instanceof XmlItem item) item.refreshTree();
            table.refresh();
        });
        verticalPills.selectedProperty().addListener((o, was, on) -> {
            verticalPills.setText(on ? "Horizontal pills" : "Vertical pills");
            table.refresh();
        });
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var bar = new HBox(8, spacer, contractLeaves, verticalPills);
        bar.getStyleClass().add("docview-xml-tree-bar");
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(6, 12, 6, 12));

        table.getStyleClass().add("docview-xml-tree-table");
        table.setShowRoot(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        // Like the WebView grid: the tag column is max(content estimate, 25%),
        // the value column takes the rest.
        table.setColumnResizePolicy(TreeTableView.UNCONSTRAINED_RESIZE_POLICY);
        table.widthProperty().addListener((o, was, width) -> layoutColumns());
        tagColumn.setSortable(false);
        valueColumn.setSortable(false);
        tagColumn.setCellValueFactory(cell -> cell.getValue().valueProperty());
        valueColumn.setCellValueFactory(cell -> cell.getValue().valueProperty());
        tagColumn.setCellFactory(column -> new TagCell());
        valueColumn.setCellFactory(column -> new ValueCell());
        table.getColumns().setAll(List.of(tagColumn, valueColumn));
        table.addEventFilter(KeyEvent.KEY_PRESSED, this::key);
        // An optional double-click action; otherwise the table's own handling.
        table.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (onDoubleClick != null && event.getButton() == MouseButton.PRIMARY
                    && event.getClickCount() == 2) {
                onDoubleClick.run();
                event.consume();
            }
        });

        root.setTop(bar);
        root.setCenter(table);
    }

    /** Copy shortcut (default Shortcut+C); null disables it. */
    public void setCopyKey(KeyCombination key) {
        copyKey = key;
    }

    /** Select-all shortcut (default Shortcut+A); null disables it. */
    public void setSelectAllKey(KeyCombination key) {
        selectAllKey = key;
    }

    /** Colors for this view (default {@link ReaderTheme#dark()}). */
    public void setTheme(ReaderTheme value) {
        theme = java.util.Objects.requireNonNull(value);
        applyThemeStyle();
        if (errorText != null && tree != null) showError(tree, errorText);
        table.refresh();
    }

    public ReaderTheme theme() {
        return theme;
    }

    /** Defines the looked-up colors used by xml-tree-view.css. */
    private void applyThemeStyle() {
        root.setStyle("-docview-background:" + ReaderTheme.css(theme.background())
                + "; -docview-text:" + ReaderTheme.css(theme.text())
                + "; -docview-divider:" + ReaderTheme.css(theme.divider())
                + "; -docview-pill-background:" + ReaderTheme.css(theme.pillBackground())
                + "; -docview-pill-border:" + ReaderTheme.css(theme.pillBorder())
                + "; -docview-header-text:" + ReaderTheme.css(theme.headerText())
                + "; -docview-muted:" + ReaderTheme.css(theme.muted())
                + "; -docview-selection:" + ReaderTheme.css(theme.selection()) + ";");
    }

    public Node node() {
        return root;
    }

    /**
     * Runs {@code action} on a primary double-click (and consumes it); null
     * (the default) leaves double-clicks to the view.
     */
    public void setOnDoubleClick(Runnable action) {
        onDoubleClick = action;
    }

    public void requestFocus() {
        table.requestFocus();
    }

    /**
     * Shows {@code next} for {@code documentPath}, restoring its expansion and
     * scroll. {@code text} is the source, shown read-only if parsing failed.
     */
    public void show(Path documentPath, XmlTree.Tree next, ReaderStyle style, String text) {
        rememberPosition();
        path = documentPath;
        tree = next;
        em = style.fontSize();
        String family = BlockView.codeFamily(style.codeFontFamily());
        code = Font.font(family, FontWeight.NORMAL, FontPosture.REGULAR, em);
        codeBold = Font.font(family, FontWeight.BOLD, FontPosture.REGULAR, em);
        if (next.root() == null) {
            showError(next, text);
            return;
        }
        errorText = null;
        root.setCenter(table);
        root.getTop().setVisible(true);
        root.getTop().setManaged(true);
        Remembered state = remembered.get(documentPath);
        XmlItem item = state != null && state.tree() == next ? state.root() : new XmlItem(next.root());
        table.setRoot(item);
        layoutColumns();
        table.getSelectionModel().clearSelection();
        table.scrollTo(state != null && state.tree() == next ? state.firstRow() : 0);
        table.refresh();
    }

    private void layoutColumns() {
        double width = table.getWidth();
        if (width <= 0) return;
        double tagEm = tree == null ? 16 : tree.tagColumnEm();
        double tag = Math.min(width * 0.6, Math.max(tagEm * em, width * 0.25));
        // Leave room for the vertical scroll bar.
        double value = Math.max(120, width - tag - 18);
        if (Math.abs(tagColumn.getPrefWidth() - tag) < 1
                && Math.abs(valueColumn.getPrefWidth() - value) < 1) {
            return;
        }
        tagColumn.setPrefWidth(tag);
        valueColumn.setPrefWidth(value);
        // Pill rows size to the value width; rebuild them for the new width.
        table.refresh();
    }

    public void rememberPosition() {
        if (path != null && tree != null && table.getRoot() instanceof XmlItem item) {
            remembered.put(path, new Remembered(tree, item, firstVisibleRow()));
        }
    }

    public void clear() {
        rememberPosition();
        errorText = null;
        path = null;
        tree = null;
        table.setRoot(null);
    }

    /** Selected rows as "tag\tvalue" lines; contracted leaves as "key: value; …". */
    public String selectedText() {
        var lines = new ArrayList<String>();
        for (TreeItem<XmlNode> item : table.getSelectionModel().getSelectedItems()) {
            if (item == null || item.getValue() == null) continue;
            XmlNode node = item.getValue();
            String value = node.value();
            if (item instanceof XmlItem xml && xml.showsPills()) {
                value = (value.isEmpty() ? "" : value + " ") + compactText(node.compact());
            }
            lines.add(node.tag() + "\t" + value);
        }
        return String.join("\n", lines);
    }

    static String compactText(List<Entry> entries) {
        var text = new StringBuilder();
        for (Entry entry : entries) {
            if (!text.isEmpty()) text.append("; ");
            text.append(entry.key()).append(": ").append(entry.value());
        }
        return text.toString();
    }

    private void showError(XmlTree.Tree failed, String text) {
        errorText = text;
        table.setRoot(null);
        root.getTop().setVisible(false);
        root.getTop().setManaged(false);
        var message = new Label("XML tree unavailable. " + failed.error());
        message.setWrapText(true);
        message.setTextFill(theme.xmlKey());
        message.setPadding(new Insets(10, 14, 10, 14));
        message.setMaxWidth(Double.MAX_VALUE);
        message.setBackground(new Background(new BackgroundFill(
                theme.codeBackground(), CornerRadii.EMPTY, Insets.EMPTY)));
        message.setBorder(new Border(new BorderStroke(theme.xmlKey(),
                BorderStrokeStyle.SOLID, CornerRadii.EMPTY, new BorderWidths(0, 0, 0, 4))));
        var raw = new TextArea(io.github.ghosthack.docview.SafeMarkupRenderer.fallbackText(text));
        raw.setEditable(false);
        raw.setFont(code);
        String background = ReaderTheme.css(theme.background());
        raw.setStyle("-fx-control-inner-background:" + background + ";"
                + " -fx-text-fill:" + ReaderTheme.css(theme.text()) + ";"
                + " -fx-background-color:" + background + "; -fx-focus-color:transparent;"
                + " -fx-faint-focus-color:transparent;");
        VBox.setVgrow(raw, Priority.ALWAYS);
        var box = new VBox(message, raw);
        box.setStyle("-fx-background-color:" + background + ";");
        root.setCenter(box);
    }

    private void key(KeyEvent event) {
        if (copyKey != null && copyKey.match(event)) {
            String text = selectedText();
            if (!text.isEmpty()) {
                var content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
            }
            event.consume();
        } else if (selectAllKey != null && selectAllKey.match(event)) {
            table.getSelectionModel().selectAll();
            event.consume();
        } else if (event.getCode() == KeyCode.SPACE) {
            TreeItem<XmlNode> focused = table.getFocusModel().getFocusedItem();
            if (focused != null && !focused.isLeaf()) focused.setExpanded(!focused.isExpanded());
            event.consume();
        }
    }

    private int firstVisibleRow() {
        if (table.lookup(".virtual-flow") instanceof VirtualFlow<?> flow
                && flow.getFirstVisibleCell() != null) {
            return flow.getFirstVisibleCell().getIndex();
        }
        return 0;
    }

    /**
     * Lazily built tree item. Contraction = the global toggle, flipped by a
     * per-branch override. Contracted, a branch lists only its structural
     * children and shows its leaves as pills; an all-leaf branch keeps its
     * leaves as hidden children so expanding it restores them (as the
     * WebView tree does), and collapsing contracts it again.
     */
    private final class XmlItem extends TreeItem<XmlNode> {
        private boolean built;
        private boolean override;

        XmlItem(XmlNode node) {
            super(node);
            boolean allLeaf = !node.hasStructuralChildren() && !node.compact().isEmpty();
            // An all-leaf branch is open exactly when leaves are restored, so
            // rows rebuilt under "Restore leaves" match their siblings.
            setExpanded(node.expandedByDefault() || allLeaf && !contractLeaves.isSelected());
            if (allLeaf) {
                expandedProperty().addListener((o, was, open) -> {
                    override = open && contractLeaves.isSelected();
                    refreshChildren();
                    table.refresh();
                });
            }
        }

        boolean contracted() {
            return contractLeaves.isSelected() != override;
        }

        boolean showsPills() {
            XmlNode node = getValue();
            return !node.compact().isEmpty() && contracted()
                    && (node.hasStructuralChildren() || !isExpanded());
        }

        void toggleBranch() {
            override = !override;
            refreshChildren();
            table.refresh();
        }

        @Override
        public boolean isLeaf() {
            return getValue().children().isEmpty();
        }

        @Override
        public ObservableList<TreeItem<XmlNode>> getChildren() {
            if (!built) {
                built = true;
                super.getChildren().setAll(rows());
            }
            return super.getChildren();
        }

        private List<TreeItem<XmlNode>> rows() {
            XmlNode node = getValue();
            boolean hideLeaves = contracted() && node.hasStructuralChildren();
            var rows = new ArrayList<TreeItem<XmlNode>>(node.children().size());
            for (XmlNode child : node.children()) {
                if (hideLeaves && child.leaf()) continue;
                rows.add(new XmlItem(child));
            }
            return rows;
        }

        void refreshChildren() {
            if (built) super.getChildren().setAll(rows());
        }

        /** Re-applies the global toggle to every already-built branch. */
        void refreshTree() {
            override = false;
            if (!getValue().hasStructuralChildren() && !getValue().compact().isEmpty()) {
                setExpanded(!contractLeaves.isSelected());
            }
            if (!built) return;
            refreshChildren();
            for (TreeItem<XmlNode> child : super.getChildren()) {
                if (child instanceof XmlItem item) item.refreshTree();
            }
        }
    }

    /**
     * A flow that always measures at its wrap length. Tree table rows ask for
     * a cell's height at the column's current width, which can still be the
     * pre-resize width; honoring it would size rows for one pill per line.
     */
    private static final class PillFlow extends FlowPane {
        PillFlow(double hgap, double vgap) {
            super(hgap, vgap);
        }

        @Override
        protected double computePrefHeight(double width) {
            return super.computePrefHeight(getPrefWrapLength());
        }

        @Override
        protected double computeMinHeight(double width) {
            return computePrefHeight(width);
        }
    }

    private final class TagCell extends TreeTableCell<XmlNode, XmlNode> {
        TagCell() {
            setAlignment(Pos.TOP_LEFT);
        }

        @Override
        protected void updateItem(XmlNode node, boolean empty) {
            super.updateItem(node, empty);
            setText(null);
            if (empty || node == null) {
                setGraphic(null);
                return;
            }
            var label = new Label(node.tag());
            label.setTextOverrun(OverrunStyle.ELLIPSIS);
            label.setMinWidth(0);
            switch (node.kind()) {
                case ELEMENT -> {
                    label.setFont(codeBold);
                    label.setTextFill(theme.xmlTag());
                }
                case PROPERTY -> {
                    label.setFont(code);
                    label.setTextFill(theme.xmlKey());
                }
                case LIMIT -> {
                    label.setFont(Font.font(code.getFamily(), FontPosture.ITALIC, em));
                    label.setTextFill(theme.muted());
                }
            }
            var box = new HBox(6, label);
            box.setAlignment(Pos.TOP_LEFT);
            box.setMaxHeight(Region.USE_PREF_SIZE);
            // Per-branch contraction toggle for branches with both leaves
            // and structural children (all-leaf branches use expansion).
            if (getTableRow() != null && getTableRow().getTreeItem() instanceof XmlItem item
                    && node.hasStructuralChildren() && !node.compact().isEmpty()) {
                var toggle = new Label(item.contracted() ? "⋯" : "⋮");
                toggle.setTextFill(theme.muted());
                toggle.setPadding(new Insets(0, 4, 0, 4));
                toggle.setBorder(new Border(new BorderStroke(theme.pillBorder(), BorderStrokeStyle.SOLID,
                        new CornerRadii(3), BorderWidths.DEFAULT)));
                toggle.setTooltip(new Tooltip("Toggle leaf contraction for this branch"));
                toggle.setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY) item.toggleBranch();
                    event.consume();
                });
                box.getChildren().add(toggle);
            }
            setGraphic(box);
        }
    }

    private final class ValueCell extends TreeTableCell<XmlNode, XmlNode> {
        ValueCell() {
            setAlignment(Pos.TOP_LEFT);
        }

        @Override
        protected void updateItem(XmlNode node, boolean empty) {
            super.updateItem(node, empty);
            setText(null);
            if (empty || node == null || node.kind() == Kind.LIMIT) {
                setGraphic(null);
                return;
            }
            boolean pills = getTableRow() != null
                    && getTableRow().getTreeItem() instanceof XmlItem item && item.showsPills();
            // Contracted rows show their leaves as pills instead of the
            // direct value, as the WebView tree does.
            if (pills) {
                setGraphic(pills(node.compact()));
                return;
            }
            var value = new Label(node.value().isEmpty() ? "—" : node.value());
            value.setWrapText(true);
            value.setMinWidth(0);
            value.setFont(code);
            value.setTextFill(node.value().isEmpty() ? theme.muted()
                    : node.kind() == Kind.PROPERTY ? theme.xmlValue() : theme.text());
            setGraphic(value);
        }

        /**
         * Vertical pills (default) are fixed-width cards with a small key above
         * the value, flowing across the column; horizontal pills put key and
         * value side by side.
         */
        private Pane pills(List<Entry> entries) {
            var pane = new PillFlow(0.4 * em, 0.3 * em);
            // An explicit width makes the row height match the laid-out pills;
            // layoutColumns() refreshes the rows whenever the width changes.
            double wrap = Math.max(160, valueColumn.getPrefWidth() - 1.3 * em);
            pane.setPrefWrapLength(wrap);
            pane.setPrefWidth(wrap);
            pane.setMaxWidth(wrap);
            boolean vertical = verticalPills.isSelected();
            for (Entry entry : entries) {
                var key = new Label(entry.key());
                key.setTextFill(theme.xmlKey());
                key.setTextOverrun(OverrunStyle.ELLIPSIS);
                key.setMinWidth(0);
                var value = new Label(entry.value());
                value.setFont(code);
                value.setTextFill(entry.value().equals("—") ? theme.muted() : theme.xmlValue());
                value.setTextOverrun(OverrunStyle.ELLIPSIS);
                value.setMinWidth(0);
                Region pill;
                if (vertical) {
                    key.setFont(Font.font(code.getFamily(), FontWeight.BOLD, em * 0.5));
                    var card = new VBox(1, key, value);
                    card.setPadding(new Insets(0.2 * em, 0.5 * em, 0.2 * em, 0.5 * em));
                    double width = 13.5 * em;
                    card.setPrefWidth(width);
                    card.setMinWidth(width);
                    card.setMaxWidth(width);
                    pill = card;
                } else {
                    key.setFont(code);
                    var inline = new HBox(6, key, value);
                    inline.setAlignment(Pos.CENTER_LEFT);
                    inline.setPadding(new Insets(0.08 * em, 0.5 * em, 0.08 * em, 0.5 * em));
                    inline.setMaxWidth(Region.USE_PREF_SIZE);
                    pill = inline;
                }
                pill.setBackground(new Background(new BackgroundFill(
                        theme.pillBackground(), new CornerRadii(0.55 * em), Insets.EMPTY)));
                pill.setBorder(new Border(new BorderStroke(theme.pillBorder(), BorderStrokeStyle.SOLID,
                        new CornerRadii(0.55 * em), BorderWidths.DEFAULT)));
                Tooltip.install(pill, new Tooltip(entry.key() + ": " + entry.value()));
                pane.getChildren().add(pill);
            }
            return pane;
        }
    }
}
