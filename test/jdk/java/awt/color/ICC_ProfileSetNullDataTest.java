/*
 * Copyright (c) 2004, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;

/**
 * @test
 * @bug 4823896 7042594
 * @summary Test checks behavior of the ICC_Profile.setData(int, byte[])
 */
public final class ICC_ProfileSetNullDataTest {
    private static final int[] colorSpace = {
            ColorSpace.CS_sRGB, ColorSpace.CS_LINEAR_RGB,
            ColorSpace.CS_PYCC, ColorSpace.CS_GRAY,
            ColorSpace.CS_CIEXYZ
    };

    public static void main(String[] args) {
        byte[] tagData = null;
        for (int cs : colorSpace) {
            ICC_Profile builtInProfile = ICC_Profile.getInstance(cs);
            ICC_Profile profile = ICC_Profile.getInstance(builtInProfile.getData());
            try {
                profile.setData(ICC_Profile.icSigCmykData, tagData);
                throw new RuntimeException("IllegalArgumentException expected");
            } catch (IllegalArgumentException e) {
                // IAE expected
            }
        }
    }
}
