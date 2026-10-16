/**
 * Copyright (c) 2015-2017 Angelo ZERR.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Initial code from https://github.com/microsoft/vscode-textmate/
 * Initial copyright Copyright (C) Microsoft Corporation. All rights reserved.
 * Initial license: MIT
 *
 * Contributors:
 * - Microsoft Corporation: Initial code, written in TypeScript, licensed under MIT license
 * - Angelo Zerr <angelo.zerr@gmail.com> - translation and adaptation to Java
 */
// Modified for docview; changes are listed in textmate/src/main/tm4e/README.md.
package io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.theme.raw;

import java.util.List;

import io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.parser.PropertySettable;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.parser.TMParser;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.parser.TMParser.ObjectFactory;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.parser.TMParserJSON;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.parser.TMParserPList;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.registry.IThemeSource;

/**
 * TextMate Theme reader utilities.
 */
public final class RawThemeReader {

	public static final ObjectFactory<RawTheme> OBJECT_FACTORY = new ObjectFactory<>() {
		@Override
		public RawTheme createRoot() {
			return new RawTheme();
		}

		@Override
		public PropertySettable<?> createChild(final TMParser.PropertyPath path, final Class<?> sourceType) {
			return List.class.isAssignableFrom(sourceType)
					? new PropertySettable.ArrayList<>()
					: new RawTheme();
		}
	};

	public static IRawTheme readTheme(final IThemeSource source) throws Exception {
		try (var reader = source.getReader()) {
			return switch (source.getContentType()) {
				case JSON -> TMParserJSON.INSTANCE.parse(reader, OBJECT_FACTORY);
				case YAML -> throw new UnsupportedOperationException(
						"YAML sources are not supported (docview bundles JSON/plist only)");
				default -> TMParserPList.INSTANCE.parse(reader, OBJECT_FACTORY);
			};
		}
	}

	/**
	 * methods should be accessed statically
	 */
	private RawThemeReader() {
	}
}
