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

package com.akiban.server.types;

import com.persistit.exception.ConversionException;

abstract class AbstractConverter {
    public final void convert(ConversionSource source, ConversionTarget target) {
        if (source.isNull()) {
            target.putNull();
        }
        else {
            doConvert(source, target);
        }
    }

    protected abstract void doConvert(ConversionSource source, ConversionTarget target);

    protected final RuntimeException unsupportedConversion(AkType unsupportedType) {
        return new ConversionException("can't convert from type " + unsupportedType);
    }
}