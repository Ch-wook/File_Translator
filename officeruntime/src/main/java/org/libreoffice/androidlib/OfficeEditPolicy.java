package org.libreoffice.androidlib;

import org.json.JSONObject;

/** Exact read-only UNO allowlist. Core view readonly remains the primary barrier. */
public final class OfficeEditPolicy {
    private OfficeEditPolicy() { }
    public static boolean allowsViewUno(String argument) {
        if (argument == null) return false;
        int space = argument.indexOf(' ');
        String command = space < 0 ? argument : argument.substring(0, space);
        if (".uno:Copy".equals(command) || ".uno:SelectAll".equals(command)) return true;
        if (!".uno:ExecuteSearch".equals(command) || space < 0) return false;
        try {
            JSONObject values = new JSONObject(argument.substring(space + 1));
            JSONObject item = values.optJSONObject("SearchItem.Command");
            if (item == null) return false;
            int search = item.optInt("value", -1);
            // UNO SearchCommand: FIND=0, FIND_ALL=1; REPLACE/REPLACE_ALL mutate.
            return search == 0 || search == 1;
        } catch (Exception invalid) { return false; }
    }
}
