/**
 * Copyright (c) 2022 Sebastian Thomschke and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
// Modified for docview; changes are listed in textmate/src/main/tm4e/README.md.
package io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.grammar.raw;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.function.BiConsumer;

import org.eclipse.jdt.annotation.Nullable;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.internal.parser.PropertySettable;

final class RawCaptures extends PropertySettable.HashMap<IRawRule> implements IRawCaptures {

	private static final Logger LOGGER = System.getLogger(RawCaptures.class.getName());

	private static final long serialVersionUID = 1L;

	@Override
	public @Nullable IRawRule getCapture(final String captureId) {
		try {
			return get(captureId);
		} catch (final ClassCastException ex) {
			// log ClassCastException with some context, to better troubleshoot issues like https://github.com/eclipse-tm4e/tm4e/issues/754
			LOGGER.log(Level.ERROR, "Unexpected ClassCastException in RawCaptures.getCapture(\"" + captureId + "\")", ex);
			throw ex;
		}
	}

	@Override
	public Iterable<String> getCaptureIds() {
		return keySet().stream()
				.filter(RawCaptures::isNumericCaptureId)
				.toList();
	}

	@Override
	public void forEachCapture(final BiConsumer<String, IRawRule> action) {
		forEach((final String captureId, final Object rule) -> {
			// vscode-textmate ignores non-numeric properties in capture maps.
			// Some current bundled grammars contain such metadata.
			if (!isNumericCaptureId(captureId))
				return;
			try {
				action.accept(captureId, (IRawRule) rule);
			} catch (final ClassCastException ex) {
				// log ClassCastException with some context, to better troubleshoot issues like https://github.com/eclipse-tm4e/tm4e/issues/754
				LOGGER.log(Level.ERROR, "Unexpected ClassCastException in RawCaptures.getCapture(\"" + captureId + "\")", ex);
				throw ex;
			}
		});
	}

	private static boolean isNumericCaptureId(final String captureId) {
		if (captureId.isEmpty())
			return false;
		for (int i = 0; i < captureId.length(); i++) {
			if (!Character.isDigit(captureId.charAt(i)))
				return false;
		}
		return true;
	}
}
