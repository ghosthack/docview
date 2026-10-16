package io.github.ghosthack.docview.fx;

import io.github.ghosthack.docview.HighlightRole;
import io.github.ghosthack.docview.HighlightToken;
import io.github.ghosthack.docview.Blocks;
import io.github.ghosthack.docview.Blocks.CodeLine;
import io.github.ghosthack.docview.Blocks.Heading;
import io.github.ghosthack.docview.Blocks.ListItem;
import io.github.ghosthack.docview.Blocks.Paragraph;
import io.github.ghosthack.docview.Blocks.Row;
import io.github.ghosthack.docview.Blocks.Rule;
import io.github.ghosthack.docview.Blocks.Run;
import io.github.ghosthack.docview.Blocks.TableRow;
import io.github.ghosthack.docview.ReaderStyle;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.geometry.Bounds;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.PathElement;
import javafx.scene.text.HitInfo;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.WeakHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Native Markdown and HTML reader: a virtualized list of {@link Blocks} rows
 * drawn with JavaFX text layout. Only rows on screen exist as nodes, so
 * document size barely affects how quickly a document appears. Fonts and
 * spacing match {@link io.github.ghosthack.docview.SafeMarkupRenderer} pages; colors come from a
 * {@link ReaderTheme}.
 *
 * <p>Mouse selection spans rows; the copy shortcut (default Shortcut+C) copies
 * it and the select-all shortcut (default Shortcut+A) selects everything.
 * Table rows select whole. Copies omit line numbers and list markers. Link
 * activation hands the raw, untrusted target string to the callback; validate
 * it before opening anything. Limits: no letter spacing, and code lines
 * always wrap.
 */
public final class BlockView {

    private static final String LINK_KEY = "native-reader-link";
    private static final double[] HEADING_EM = {2.0, 1.5, 1.17, 1.0, 0.83, 0.67};
    /** The page stylesheet's font fallbacks, resolved against installed fonts. */
    private static final List<String> PROSE_FALLBACKS = List.of("Iowan Old Style",
            "Charter", "Palatino Linotype", "Palatino", "Constantia", "Georgia", "Serif");
    private static final List<String> CODE_FALLBACKS = List.of("SF Mono", "Menlo",
            "Consolas", "DejaVu Sans Mono", "Monospaced");
    private static Set<String> installedFamilies;
    /** Arrow-key scroll step in px, matching the WebView page. */
    private static final double ARROW_STEP = 40;
    private static final int REMEMBERED_DOCUMENTS = 64;

    private final ListView<Row> list = new ListView<>();
    private ReaderTheme theme = ReaderTheme.dark();
    /** Scroll row per recently shown document (bounded LRU). */
    private final Map<Path, Integer> rememberedRows = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Integer> eldest) {
            return size() > REMEMBERED_DOCUMENTS;
        }
    };
    private Blocks.Model model;
    private Path path;
    private ReaderStyle style;
    private Fonts fonts;
    private Consumer<String> linkActivated = ignored -> { };
    private Runnable onDoubleClick;
    private KeyCombination copyKey =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private KeyCombination selectAllKey =
            new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);
    /** Cells currently owned by the list; ListView may discard and recreate them. */
    private final Set<RowCell> cells = Collections.newSetFromMap(new WeakHashMap<>());
    /** Selection endpoints in (row, text offset); equal or null means none. */
    private Position anchor;
    private Position focus;
    private boolean selecting;

    /** A caret position: a row and an offset into its {@link #rowText}. */
    record Position(int row, int offset) implements Comparable<Position> {
        @Override
        public int compareTo(Position other) {
            return row != other.row ? Integer.compare(row, other.row)
                    : Integer.compare(offset, other.offset);
        }
    }

    public BlockView() {
        list.setFocusTraversable(true);
        applyListStyle();
        list.setCellFactory(ignored -> new RowCell());
        // Reader scrolls; it never selects rows (cells also never paint
        // selection). Presses pass through so the scroll bar still drags.
        list.addEventFilter(KeyEvent.KEY_PRESSED, this::scrollKey);
        list.addEventFilter(MouseEvent.MOUSE_PRESSED, this::pressed);
        list.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::dragged);
        // Synthetic input (e.g. JavaFX Robot) moves with the button held
        // without producing MOUSE_DRAGGED; treat it as a drag.
        list.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (selecting && event.isPrimaryButtonDown()) dragged(event);
        });
        list.addEventFilter(MouseEvent.MOUSE_RELEASED, this::released);
        list.widthProperty().addListener((o, was, width) -> list.refresh());
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
        applyListStyle();
        list.refresh();
    }

    public ReaderTheme theme() {
        return theme;
    }

    private void applyListStyle() {
        String background = ReaderTheme.css(theme.background());
        list.setStyle("-fx-background-color:" + background + "; -fx-background-insets:0;"
                + " -fx-padding:0; -fx-border-width:0;"
                + " -fx-control-inner-background:" + background + ";"
                + " -fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
    }

    public Node node() {
        return list;
    }

    public void setLinkActivated(Consumer<String> action) {
        linkActivated = action == null ? ignored -> { } : action;
    }

    /**
     * Runs {@code action} on a primary double-click (and consumes it); null
     * (the default) leaves double-clicks to the view.
     */
    public void setOnDoubleClick(Runnable action) {
        onDoubleClick = action;
    }

    /**
     * Shows {@code next} for {@code documentPath}, restoring the row last
     * shown for that path. The view never closes models: the caller (or a
     * {@link io.github.ghosthack.docview.BlockCache}) owns them. An unclosed
     * model only holds memory and is reclaimed once unreachable.
     */
    public void show(Path documentPath, Blocks.Model next, ReaderStyle readerStyle) {
        rememberPosition();
        model = next;
        path = documentPath;
        style = readerStyle;
        anchor = focus = null;
        fonts = new Fonts(readerStyle);
        list.getItems().setAll(next.rows());
        int row = rememberedRows.getOrDefault(documentPath, 0);
        list.scrollTo(Math.max(0, Math.min(row, next.rows().size() - 1)));
    }

    /** Records the current document's first visible row for a later return. */
    public void rememberPosition() {
        if (path != null && model != null) rememberedRows.put(path, firstVisibleRow());
    }

    public void clear() {
        rememberPosition();
        anchor = focus = null;
        model = null;
        path = null;
        list.getItems().clear();
    }

    public boolean scrollToAnchor(String anchor) {
        if (model == null) return false;
        int row = model.rowOfAnchor(anchor);
        if (row < 0) return false;
        list.scrollTo(row);
        return true;
    }

    public void requestFocus() {
        list.requestFocus();
    }

    private int firstVisibleRow() {
        VirtualFlow<?> flow = flow();
        if (flow == null || flow.getFirstVisibleCell() == null) return 0;
        return flow.getFirstVisibleCell().getIndex();
    }

    private VirtualFlow<?> flow() {
        return list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow ? flow : null;
    }

    private void scrollKey(KeyEvent event) {
        if (copyKey != null && copyKey.match(event)) {
            String text = selectedText();
            if (!text.isEmpty()) {
                var content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
            }
            event.consume();
            return;
        }
        if (selectAllKey != null && selectAllKey.match(event)) {
            selectAll();
            event.consume();
            return;
        }
        VirtualFlow<?> flow = flow();
        if (flow == null) return;
        double page = Math.max(ARROW_STEP, list.getHeight() - ARROW_STEP);
        switch (event.getCode()) {
            case UP, K -> flow.scrollPixels(-ARROW_STEP);
            case DOWN, J -> flow.scrollPixels(ARROW_STEP);
            case PAGE_UP -> flow.scrollPixels(-page);
            case PAGE_DOWN, SPACE -> flow.scrollPixels(event.isShiftDown() ? -page : page);
            case HOME -> list.scrollTo(0);
            case END -> list.scrollTo(Math.max(0, list.getItems().size() - 1));
            default -> {
                return;
            }
        }
        event.consume();
    }

    // --- selection --------------------------------------------------------

    /** Plain text a row contributes to copies (no markers or line numbers). */
    static String rowText(Row row) {
        return switch (row) {
            case Heading heading -> runText(heading.runs());
            case Paragraph paragraph -> runText(paragraph.runs());
            case ListItem item -> runText(item.runs());
            case CodeLine line -> line.text();
            case TableRow tableRow -> {
                var cellsText = new ArrayList<String>();
                for (List<Run> cell : tableRow.cells()) cellsText.add(runText(cell));
                yield String.join("\t", cellsText);
            }
            case Rule ignored -> "";
        };
    }

    private static String runText(List<Run> runs) {
        var out = new StringBuilder();
        for (Run run : runs) out.append(run.text());
        return out.toString();
    }

    /** Selected text, rows joined by newlines; empty when nothing is selected. */
    public String selectedText() {
        if (model == null || anchor == null || focus == null || anchor.equals(focus)) {
            return "";
        }
        Position start = anchor.compareTo(focus) <= 0 ? anchor : focus;
        Position end = start == anchor ? focus : anchor;
        var out = new StringBuilder();
        for (int row = start.row(); row <= end.row(); row++) {
            String text = rowText(model.rows().get(row));
            int from = row == start.row() ? Math.min(start.offset(), text.length()) : 0;
            int to = row == end.row() ? Math.min(end.offset(), text.length()) : text.length();
            if (row > start.row()) out.append('\n');
            if (from < to) out.append(text, from, to);
        }
        return out.toString();
    }

    public void selectAll() {
        if (model == null || model.rows().isEmpty()) return;
        int last = model.rows().size() - 1;
        anchor = new Position(0, 0);
        focus = new Position(last, rowText(model.rows().get(last)).length());
        refreshSelection();
    }

    private void pressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || onScrollBar(event)) return;
        if (event.getClickCount() == 2 && onDoubleClick != null) {
            onDoubleClick.run();
            event.consume();
            return;
        }
        list.requestFocus();
        Position at = positionAt(event.getSceneX(), event.getSceneY());
        anchor = focus = at;
        selecting = at != null;
        refreshSelection();
        // Keep ListView from selecting/scrolling the pressed row.
        event.consume();
    }

    /** A click that did not drag activates the link under the pointer. */
    private void released(MouseEvent event) {
        boolean dragged = anchor != null && !anchor.equals(focus);
        selecting = false;
        if (dragged || event.getButton() != MouseButton.PRIMARY) return;
        if (event.getPickResult().getIntersectedNode() instanceof Text text
                && text.getProperties().get(LINK_KEY) instanceof String target) {
            Platform.runLater(() -> linkActivated.accept(target));
        }
    }

    private void dragged(MouseEvent event) {
        if (!selecting) return;
        Bounds bounds = list.localToScene(list.getBoundsInLocal());
        VirtualFlow<?> flow = flow();
        if (flow != null) {
            // Auto-scroll while dragging past the top or bottom edge.
            if (event.getSceneY() < bounds.getMinY()) {
                flow.scrollPixels(-Math.min(60, bounds.getMinY() - event.getSceneY()));
            } else if (event.getSceneY() > bounds.getMaxY()) {
                flow.scrollPixels(Math.min(60, event.getSceneY() - bounds.getMaxY()));
            }
        }
        Position at = positionAt(event.getSceneX(),
                Math.max(bounds.getMinY() + 1, Math.min(bounds.getMaxY() - 1, event.getSceneY())));
        if (at != null) {
            focus = at;
            refreshSelection();
        }
        event.consume();
    }

    private static boolean onScrollBar(MouseEvent event) {
        for (Node node = event.getPickResult().getIntersectedNode(); node != null;
                node = node.getParent()) {
            if (node instanceof ScrollBar) return true;
        }
        return false;
    }

    /** The row/offset under a scene point, clamped to the visible rows. */
    private Position positionAt(double sceneX, double sceneY) {
        RowCell above = null;
        RowCell below = null;
        for (RowCell cell : cells) {
            if (!cell.live()) continue;
            Bounds bounds = cell.localToScene(cell.getBoundsInLocal());
            if (sceneY >= bounds.getMinY() && sceneY < bounds.getMaxY()) {
                return cell.positionAt(sceneX, sceneY);
            }
            if (bounds.getMaxY() <= sceneY
                    && (above == null || cell.getIndex() > above.getIndex())) above = cell;
            if (bounds.getMinY() > sceneY
                    && (below == null || cell.getIndex() < below.getIndex())) below = cell;
        }
        if (below != null && above == null) return new Position(below.getIndex(), 0);
        if (above != null) {
            return new Position(above.getIndex(), rowText(above.getItem()).length());
        }
        return null;
    }

    private void refreshSelection() {
        for (RowCell cell : cells) cell.updateSelection();
    }

    /** Readable column: max 78em centred, at least 2em of side padding. */
    private double sidePadding() {
        double em = style.fontSize();
        return Math.max(2 * em, (list.getWidth() - 78 * em) / 2);
    }

    private double contentWidth() {
        // Leave room for the vertical scroll bar.
        return Math.max(80, list.getWidth() - 2 * sidePadding() - 16);
    }

    private record Fonts(String proseFamily, Font prose, Font proseBold,
                         Font proseBoldItalic, Font code, Font codeSmall,
                         Font symbol, double em, double lineSpacing) {
        Fonts(ReaderStyle style) {
            this(style, family(style.readerFontFamily(), PROSE_FALLBACKS),
                    family(style.codeFontFamily(), CODE_FALLBACKS));
        }

        private Fonts(ReaderStyle style, String prose, String code) {
            this(prose, font(prose, style, style.bold(), style.italic(), 1),
                    font(prose, style, true, style.italic(), 1),
                    font(prose, style, true, true, 1),
                    font(code, style, false, false, 1),
                    font(code, style, false, false, 0.92),
                    Font.font("Apple Symbols", style.fontSize() * 1.25),
                    style.fontSize(),
                    Math.max(0, (style.lineSpacing() - 1) * style.fontSize()));
        }

        /** First installed of {@code preferred} then {@code fallbacks}, like CSS. */
        private static String family(String preferred, List<String> fallbacks) {
            Set<String> installed = installedFamilies();
            if (preferred != null && installed.contains(preferred)) return preferred;
            for (String fallback : fallbacks) {
                if (installed.contains(fallback)) return fallback;
            }
            return fallbacks.getLast();
        }

        private static Font font(String family, ReaderStyle style, boolean bold,
                                 boolean italic, double scale) {
            return Font.font(family, bold ? FontWeight.BOLD : FontWeight.NORMAL,
                    italic ? FontPosture.ITALIC : FontPosture.REGULAR,
                    style.fontSize() * scale);
        }

        /**
         * The Reader stylesheet makes {@code em} bold italic
         * ({@code font-weight:700}); HTML {@code i} stays plain italic.
         */
        Font proseFor(Run run, boolean bold, double scale) {
            boolean strong = bold || run.strong() || run.emphasis();
            boolean italic = run.emphasis() || run.italic();
            if (scale != 1 || (italic && !strong)) {
                return Font.font(proseFamily, strong ? FontWeight.BOLD : FontWeight.NORMAL,
                        italic ? FontPosture.ITALIC : FontPosture.REGULAR,
                        prose.getSize() * scale);
            }
            return italic ? proseBoldItalic : strong ? proseBold : prose;
        }
    }

    /**
     * {@link TextFlow} lays out a non-{@link Text} child (an inline code chip)
     * as a single character. These map between that layout index and offsets
     * into the row's plain text; a chip selects as a unit.
     */
    static int layoutToText(TextFlow flow, int layoutIndex) {
        int layout = 0;
        int text = 0;
        for (Node child : flow.getChildren()) {
            if (child instanceof Text node) {
                int length = node.getText().length();
                if (layoutIndex <= layout + length) return text + (layoutIndex - layout);
                layout += length;
                text += length;
            } else if (child instanceof Label chip) {
                if (layoutIndex <= layout) return text;
                layout += 1;
                text += chip.getText().length();
            }
        }
        return text;
    }

    static int textToLayout(TextFlow flow, int textOffset) {
        int layout = 0;
        int text = 0;
        for (Node child : flow.getChildren()) {
            if (child instanceof Text node) {
                int length = node.getText().length();
                if (textOffset <= text + length) return layout + (textOffset - text);
                layout += length;
                text += length;
            } else if (child instanceof Label chip) {
                int length = chip.getText().length();
                if (textOffset <= text) return layout;
                if (textOffset < text + length) return layout + 1;
                layout += 1;
                text += length;
            }
        }
        return layout;
    }

    /** The configured code font, or the Reader stylesheet's first installed fallback. */
    static String codeFamily(String preferred) {
        return Fonts.family(preferred, CODE_FALLBACKS);
    }

    private static synchronized Set<String> installedFamilies() {
        if (installedFamilies == null) installedFamilies = Set.copyOf(Font.getFamilies());
        return installedFamilies;
    }

    private final class RowCell extends ListCell<Row> {
        /** Text flow holding this row's selectable text, if any. */
        private TextFlow selectFlow;
        private final javafx.scene.shape.Path selectPath = new javafx.scene.shape.Path();
        /** Row selected as a unit (table rows). */
        private Region wholeRow;

        RowCell() {
            setPrefWidth(0);
            setStyle("-fx-background-color:transparent; -fx-padding:0;");
            selectPath.setStroke(null);
            selectPath.setManaged(false);
            selectPath.setMouseTransparent(true);
            cells.add(this);
        }

        boolean live() {
            return !isEmpty() && getItem() != null && getIndex() >= 0
                    && isVisible() && getParent() != null;
        }

        Position positionAt(double sceneX, double sceneY) {
            String text = rowText(getItem());
            if (selectFlow == null) {
                Bounds bounds = localToScene(getBoundsInLocal());
                return new Position(getIndex(),
                        sceneX < bounds.getCenterX() ? 0 : text.length());
            }
            var local = selectFlow.sceneToLocal(sceneX, sceneY);
            Bounds flowBounds = selectFlow.getLayoutBounds();
            if (local.getY() < flowBounds.getMinY()) return new Position(getIndex(), 0);
            if (local.getY() >= flowBounds.getMaxY()) {
                return new Position(getIndex(), text.length());
            }
            HitInfo hit = selectFlow.hitTest(local);
            int offset = layoutToText(selectFlow, hit.getInsertionIndex());
            return new Position(getIndex(), Math.min(offset, text.length()));
        }

        void updateSelection() {
            selectPath.setFill(theme.selection());
            selectPath.getElements().clear();
            if (wholeRow != null) wholeRow.setBackground(null);
            tintChips(0, 0);
            if (!live() || anchor == null || focus == null || anchor.equals(focus)) return;
            Position start = anchor.compareTo(focus) <= 0 ? anchor : focus;
            Position end = start == anchor ? focus : anchor;
            int row = getIndex();
            if (row < start.row() || row > end.row()) return;
            int length = rowText(getItem()).length();
            int from = row == start.row() ? Math.min(start.offset(), length) : 0;
            int to = row == end.row() ? Math.min(end.offset(), length) : length;
            if (wholeRow != null) {
                if (from < to || (row > start.row() && row < end.row())) {
                    wholeRow.setBackground(new Background(new BackgroundFill(
                            theme.selection(), CornerRadii.EMPTY, Insets.EMPTY)));
                }
                return;
            }
            if (selectFlow == null || from >= to) return;
            PathElement[] shape = selectFlow.rangeShape(
                    textToLayout(selectFlow, from), textToLayout(selectFlow, to));
            selectPath.getElements().setAll(shape);
            tintChips(from, to);
        }

        /** Opaque code chips cover the underlay; tint those inside [from, to). */
        private void tintChips(int from, int to) {
            if (selectFlow == null) return;
            int offset = 0;
            for (Node child : selectFlow.getChildren()) {
                if (child instanceof Text text) {
                    offset += text.getText().length();
                } else if (child instanceof Label chip) {
                    int end = offset + chip.getText().length();
                    boolean selected = from < end && to > offset;
                    chip.setBackground(new Background(new BackgroundFill(
                            selected ? theme.selectedCode() : theme.codeBackground(),
                            new CornerRadii(0.2 * fonts.em()), Insets.EMPTY)));
                    offset = end;
                }
            }
        }

        /** Wraps {@code flow} with a selection underlay sharing its origin. */
        private Region selectable(TextFlow flow) {
            selectFlow = flow;
            flow.setCursor(Cursor.TEXT);
            var underlay = new Pane(selectPath);
            underlay.setManaged(false);
            underlay.setMouseTransparent(true);
            // rangeShape() is in flow coordinates; track the flow's offset.
            underlay.layoutXProperty().bind(flow.layoutXProperty());
            underlay.layoutYProperty().bind(flow.layoutYProperty());
            var stack = new StackPane(underlay, flow);
            stack.setAlignment(Pos.TOP_LEFT);
            stack.setMinWidth(0);
            return stack;
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            selectFlow = null;
            wholeRow = null;
            if (selectPath.getParent() instanceof Pane old) old.getChildren().remove(selectPath);
            if (empty || row == null || style == null) {
                setGraphic(null);
                return;
            }
            double em = fonts.em();
            Region content = switch (row) {
                case Heading heading -> heading(heading);
                case Paragraph paragraph -> paragraph(paragraph.runs(),
                        paragraph.quoteDepth() > 0 ? theme.quoteText() : theme.text());
                case ListItem item -> listItem(item);
                case CodeLine line -> codeLine(line);
                case TableRow tableRow -> tableRow(tableRow);
                case Rule ignored -> rule();
            };
            Region quoted = quote(content, row.quoteDepth());
            HBox outer = new HBox(quoted);
            HBox.setHgrow(quoted, Priority.ALWAYS);
            double side = sidePadding();
            Insets margin = margin(row, em);
            // The Reader page's 2.3em top padding, folded into the first row.
            double top = margin.getTop() + (getIndex() == 0 ? 2.3 * em - margin.getTop() : 0);
            outer.setPadding(new Insets(top, side, margin.getBottom(), side));
            setGraphic(outer);
            updateSelection();
        }

        private Insets margin(Row row, double em) {
            return switch (row) {
                case Heading heading -> new Insets(
                        (heading.level() <= 2 ? 0.9 : 0.8) * em, 0, 0.45 * em, 0);
                case Paragraph ignored -> new Insets(0.5 * em, 0, 0.5 * em, 0);
                case ListItem item -> new Insets(0.08 * em, 0,
                        item.lastInList() && item.depth() == 0 ? 0.6 * em : 0.08 * em, 0);
                case CodeLine line -> new Insets(0, 0,
                        line.line() == line.block().lineCount() - 1 ? 0.9 * em : 0, 0);
                case TableRow tableRow -> new Insets(0, 0, tableRow.last() ? 0.9 * em : 0, 0);
                case Rule ignored -> new Insets(0.5 * em, 0, 0.5 * em, 0);
            };
        }

        private Region quote(Region content, int depth) {
            Region wrapped = content;
            for (int level = 0; level < depth; level++) {
                var box = new HBox(wrapped);
                HBox.setHgrow(wrapped, Priority.ALWAYS);
                box.setBorder(new Border(new BorderStroke(theme.quoteBar(),
                        BorderStrokeStyle.SOLID, CornerRadii.EMPTY,
                        new BorderWidths(0, 0, 0, 0.25 * fonts.em()))));
                box.setPadding(new Insets(0, 0, 0, fonts.em()));
                wrapped = box;
            }
            return wrapped;
        }

        private Region heading(Heading heading) {
            double scale = HEADING_EM[heading.level() - 1];
            TextFlow flow = flow(heading.runs(), theme.heading(), true, scale);
            flow.setLineSpacing(0);
            Region selectable = selectable(flow);
            selectable.setPadding(new Insets(0, 0, 0.28 * fonts.em() * scale, 0));
            selectable.setBorder(new Border(new BorderStroke(theme.rule(), BorderStrokeStyle.SOLID,
                    CornerRadii.EMPTY, new BorderWidths(0, 0, 1, 0))));
            return selectable;
        }

        private Region paragraph(List<Run> runs, Color color) {
            return selectable(flow(runs, color, false, 1));
        }

        private Region listItem(ListItem item) {
            double em = fonts.em();
            var row = new HBox(0.24 * em);
            row.setAlignment(Pos.TOP_LEFT);
            if (item.taskTree()) {
                if (!item.connector().isEmpty()) {
                    Text connector = new Text(item.connector());
                    connector.setFont(fonts.codeSmall());
                    connector.setFill(theme.connector());
                    row.getChildren().add(connector);
                }
                Text prefix = new Text(item.prefix());
                prefix.setFont(item.task() ? fonts.symbol() : fonts.prose());
                prefix.setFill(theme.text());
                var prefixBox = new HBox(prefix);
                prefixBox.setAlignment(Pos.TOP_RIGHT);
                prefixBox.setMinWidth(1.2 * em);
                row.getChildren().add(prefixBox);
            } else {
                row.setPadding(new Insets(0, 0, 0, (1.6 * item.depth() + 0.6) * em));
                Text prefix = new Text(item.prefix());
                prefix.setFont(fonts.prose());
                prefix.setFill(theme.text());
                var prefixBox = new HBox(prefix);
                prefixBox.setAlignment(Pos.TOP_RIGHT);
                prefixBox.setMinWidth(1.4 * em);
                row.getChildren().add(prefixBox);
            }
            TextFlow label = flow(item.runs(), theme.text(), false, 1);
            if (item.checked()) {
                label.setOpacity(0.5);
                for (Node node : label.getChildren()) {
                    if (node instanceof Text text) text.setStrikethrough(true);
                }
            }
            label.setMinWidth(0);
            Region selectableLabel = selectable(label);
            HBox.setHgrow(selectableLabel, Priority.ALWAYS);
            row.getChildren().add(selectableLabel);
            return row;
        }

        private Region codeLine(CodeLine line) {
            double em = fonts.em();
            var number = new Text(Integer.toString(line.line() + 1));
            number.setFont(fonts.code());
            number.setFill(theme.lineNumber());
            var gutter = new HBox(number);
            gutter.setAlignment(Pos.TOP_RIGHT);
            double digitWidth = fonts.code().getSize() * 0.62;
            gutter.setMinWidth(line.block().digits() * digitWidth + 1.6 * em);
            gutter.setPadding(new Insets(0, 0.8 * em, 0, 0.8 * em));
            gutter.setBorder(new Border(new BorderStroke(theme.rule(), BorderStrokeStyle.SOLID,
                    CornerRadii.EMPTY, new BorderWidths(0, 1, 0, 0))));
            var code = new TextFlow();
            code.setLineSpacing(fonts.lineSpacing());
            String text = line.text();
            int at = 0;
            for (HighlightToken token : line.block().tokens(line.line(), text)) {
                if (token.start() > at) code.getChildren().add(codeText(text.substring(at, token.start()), null));
                code.getChildren().add(codeText(text.substring(token.start(), token.end()),
                        theme.syntax(token.role())));
                at = token.end();
            }
            if (at < text.length()) code.getChildren().add(codeText(text.substring(at), null));
            if (text.isEmpty()) code.getChildren().add(codeText(" ", null));
            code.setMinWidth(0);
            Region selectableCode = selectable(code);
            selectableCode.setPadding(new Insets(0, 0, 0, 0.8 * em));
            HBox.setHgrow(selectableCode, Priority.ALWAYS);
            var row = line.block().lineNumbers()
                    ? new HBox(gutter, selectableCode) : new HBox(selectableCode);
            if (!line.block().lineNumbers()) {
                selectableCode.setPadding(new Insets(0, em, 0, em));
            }
            boolean first = line.line() == 0;
            boolean last = line.line() == line.block().lineCount() - 1;
            row.setPadding(new Insets(first ? 0.75 * em : 0, 0, last ? 0.75 * em : 0, 0));
            row.setBackground(new Background(new BackgroundFill(theme.codeBackground(), CornerRadii.EMPTY, Insets.EMPTY)));
            return row;
        }

        private Text codeText(String value, Color color) {
            var text = new Text(value);
            text.setFont(fonts.code());
            text.setFill(color == null ? theme.syntax(HighlightRole.PLAIN) : color);
            return text;
        }

        private Region tableRow(TableRow tableRow) {
            double em = fonts.em();
            double width = contentWidth() - tableRow.quoteDepth() * 1.25 * em;
            double natural = tableRow.table().naturalTotalEm() * em;
            boolean fitContent = natural > 0 && natural <= width;
            var row = new HBox();
            for (int column = 0; column < tableRow.table().columns(); column++) {
                TextFlow cell = flow(tableRow.cells().get(column), theme.text(), tableRow.header(), 1);
                Blocks.Alignment alignment = tableRow.table().alignment(column);
                // Browsers centre header cells that have no explicit alignment.
                if (tableRow.header() && alignment == Blocks.Alignment.NONE) {
                    alignment = Blocks.Alignment.CENTER;
                }
                cell.setTextAlignment(switch (alignment) {
                    case CENTER -> TextAlignment.CENTER;
                    case RIGHT -> TextAlignment.RIGHT;
                    default -> TextAlignment.LEFT;
                });
                cell.setPadding(new Insets(0.35 * em, 0.6 * em, 0.35 * em, 0.6 * em));
                double cellWidth = fitContent
                        ? tableRow.table().naturalEm(column) * em
                        : width * tableRow.table().widthPercent(column) / 100;
                cell.setPrefWidth(cellWidth);
                cell.setMinWidth(cellWidth);
                cell.setMaxWidth(cellWidth);
                cell.setBorder(new Border(new BorderStroke(theme.tableBorder(), BorderStrokeStyle.SOLID,
                        CornerRadii.EMPTY, new BorderWidths(tableRow.header() ? 1 : 0, 1, 1,
                        column == 0 ? 1 : 0))));
                row.getChildren().add(cell);
            }
            wholeRow = row;
            return row;
        }

        /** Inline code with the Reader's dark chip (.28em side padding). */
        private Node codeChip(String value, double scale) {
            var chip = new Label(value);
            chip.setFont(scale == 1 ? fonts.code() : Font.font(
                    fonts.code().getFamily(), fonts.code().getSize() * scale));
            chip.setTextFill(theme.text());
            chip.setMinWidth(Region.USE_PREF_SIZE);
            double em = fonts.em() * scale;
            chip.setPadding(new Insets(0, 0.28 * em, 0, 0.28 * em));
            chip.setBackground(new Background(new BackgroundFill(theme.codeBackground(),
                    new CornerRadii(0.2 * em), Insets.EMPTY)));
            return chip;
        }

        private Region rule() {
            var rule = new Region();
            rule.setMinHeight(1);
            rule.setPrefHeight(1);
            rule.setMaxHeight(1);
            rule.setBackground(new Background(new BackgroundFill(theme.tableBorder(), CornerRadii.EMPTY, Insets.EMPTY)));
            return rule;
        }

        private TextFlow flow(List<Run> runs, Color color, boolean bold, double scale) {
            var flow = new TextFlow();
            flow.setLineSpacing(fonts.lineSpacing());
            flow.setMinWidth(0);
            for (Run run : runs) {
                if (run.code() && run.link() == null) {
                    flow.getChildren().add(codeChip(run.text(), scale));
                    continue;
                }
                var text = new Text(run.text());
                if (run.code()) {
                    text.setFont(scale == 1 ? fonts.code() : Font.font(
                            fonts.code().getFamily(), fonts.code().getSize() * scale));
                } else {
                    text.setFont(fonts.proseFor(run, bold, scale));
                }
                if (run.link() != null) {
                    text.setFill(theme.link());
                    text.setUnderline(true);
                    text.setCursor(Cursor.HAND);
                    text.getProperties().put(LINK_KEY, run.link());
                } else {
                    text.setFill(color);
                }
                flow.getChildren().add(text);
            }
            if (runs.isEmpty()) flow.getChildren().add(new Text(""));
            return flow;
        }
    }
}
