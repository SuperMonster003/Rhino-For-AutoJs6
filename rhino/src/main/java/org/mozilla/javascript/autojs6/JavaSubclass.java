/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.autojs6;

/**
 * AutoJs6 class syntax, fork only (ROADMAP D3): the marker interface of the adapter classes that
 * {@link JavaClassInheritance} generates for classes extending a Java class. Their {@code delegee}
 * and {@code self} fields hold the class instance, which wrapping the Java object returns again.
 */
public interface JavaSubclass {}
