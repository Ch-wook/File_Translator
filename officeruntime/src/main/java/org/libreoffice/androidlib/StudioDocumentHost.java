/* This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. */
package org.libreoffice.androidlib;

import android.net.Uri;
import java.io.File;

/** Application-owned storage boundary; the engine never implements provider rollback itself. */
public interface StudioDocumentHost {
    void publishResult(File source, Uri destination, String originalUri) throws Exception;
    boolean sameDocument(String left, String right);
    String describeFailure(Throwable failure);
}
