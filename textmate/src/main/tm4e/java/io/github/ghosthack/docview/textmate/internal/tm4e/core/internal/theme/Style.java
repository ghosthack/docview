/**
 * Copyright (c) 2015-2017 Angelo ZERR.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.theme;

import org.eclipse.jdt.annotation.Nullable;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.theme.IStyle;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.theme.RGB;

public class Style implements IStyle {

	private @Nullable RGB color;
	private @Nullable RGB backgroundColor;

	private boolean bold;
	private boolean italic;
	private boolean underline;
	private boolean strikeThrough;

	@Override
	public @Nullable RGB getColor() {
		return color;
	}

	public void setColor(final @Nullable RGB value) {
		color = value;
	}

	@Override
	public @Nullable RGB getBackgroundColor() {
		return backgroundColor;
	}

	public void setBackgroundColor(final @Nullable RGB value) {
		backgroundColor = value;
	}

	@Override
	public boolean isBold() {
		return bold;
	}

	public void setBold(final boolean enabled) {
		bold = enabled;
	}

	@Override
	public boolean isItalic() {
		return italic;
	}

	public void setItalic(final boolean enabled) {
		italic = enabled;
	}

	@Override
	public boolean isUnderline() {
		return underline;
	}

	public void setUnderline(final boolean enabled) {
		underline = enabled;
	}

	@Override
	public boolean isStrikeThrough() {
		return strikeThrough;
	}

	public void setStrikeThrough(final boolean enabled) {
		strikeThrough = enabled;
	}

	@Override
	public String toString() {
		return "Style [color=" + color + ", backgroundColor=" + backgroundColor + ", bold=" + bold + ", italic=" + italic + ", underline="
				+ underline + ", strikeThrough=" + strikeThrough + "]";
	}
}
