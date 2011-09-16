/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.error;

public final class AkibanRuntimeException extends RuntimeException {
    public AkibanRuntimeException(String message) {
        super(message);
    }

    public AkibanRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public AkibanRuntimeException(Throwable cause) {
        super(cause);
    }
}
