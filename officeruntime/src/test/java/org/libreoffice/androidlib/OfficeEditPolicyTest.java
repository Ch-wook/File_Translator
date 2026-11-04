package org.libreoffice.androidlib;

import org.junit.Test;
import static org.junit.Assert.*;

public class OfficeEditPolicyTest {
    @Test public void viewAllowsOnlyExactReadCommands() {
        assertTrue(OfficeEditPolicy.allowsViewUno(".uno:Copy"));
        assertTrue(OfficeEditPolicy.allowsViewUno(".uno:SelectAll"));
        for (String command : new String[] {".uno:CopySheet", ".uno:SelectAllSheets", ".uno:Cut",
                ".uno:Paste", ".uno:Undo", ".uno:Redo", ".uno:Save", ".uno:InsertTable",
                ".uno:TransformDialog", ".uno:UnknownMutation", "", "uno .uno:Copy"}) {
            assertFalse(command, OfficeEditPolicy.allowsViewUno(command));
        }
        assertFalse(OfficeEditPolicy.allowsViewUno(null));
    }
    @Test public void viewingSearchRejectsReplaceAndMalformedOrImplicitCommands() {
        for (int value : new int[] {0, 1})
            assertTrue(OfficeEditPolicy.allowsViewUno(".uno:ExecuteSearch {\"SearchItem.Command\":{\"type\":\"long\",\"value\":" + value + "}}"));
        for (int value : new int[] {-1, 2, 3, 4})
            assertFalse(OfficeEditPolicy.allowsViewUno(".uno:ExecuteSearch {\"SearchItem.Command\":{\"type\":\"long\",\"value\":" + value + "}}"));
        assertFalse(OfficeEditPolicy.allowsViewUno(".uno:ExecuteSearch"));
        assertFalse(OfficeEditPolicy.allowsViewUno(".uno:ExecuteSearch {}"));
        assertFalse(OfficeEditPolicy.allowsViewUno(".uno:ExecuteSearch not-json"));
        assertFalse(OfficeEditPolicy.allowsViewUno(".uno:ExecuteSearchAndReplace {\"SearchItem.Command\":{\"value\":0}}"));
    }
}
