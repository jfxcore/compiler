// Copyright (c) 2021, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.compiler.ast.emit;

/**
 * Indicates whether the value represented by this node can be null at runtime.
 */
public interface NullableInfo {

    boolean isNullable();

}
