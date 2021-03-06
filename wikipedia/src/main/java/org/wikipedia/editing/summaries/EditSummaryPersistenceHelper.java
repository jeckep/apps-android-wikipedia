package org.wikipedia.editing.summaries;

import android.content.ContentValues;
import android.database.Cursor;
import org.wikipedia.data.PersistenceHelper;

import java.util.Date;

public class EditSummaryPersistenceHelper extends PersistenceHelper<EditSummary> {
    @Override
    public EditSummary fromCursor(Cursor c) {
        // Carefully, get them back by using position only
        String summary = c.getString(1);
        Date lastUsed = new Date(c.getLong(2));
        return new EditSummary(summary, lastUsed);
    }

    @Override
    protected ContentValues toContentValues(EditSummary obj) {
        ContentValues contentValues = new ContentValues();
        contentValues.put("summary", obj.getSummary());
        contentValues.put("lastUsed", obj.getLastUsed().getTime());
        return contentValues;
    }

    @Override
    public String getTableName() {
        return "editsummaries";
    }

    @Override
    protected int getDBVersionIntroducedAt() {
        return 2;
    }

    @Override
    public Column[] getColumnsAdded(int version) {
        switch (version) {
            case 2:
                return new Column[] {
                        new Column("_id", "integer primary key"),
                        new Column("summary", "string"),
                        new Column("lastUsed", "integer")
                };
            default:
                return new Column[0];
        }
    }

    @Override
    protected String getPrimaryKeySelection() {
        return "summary = ?";
    }

    @Override
    protected String[] getPrimaryKeySelectionArgs(EditSummary obj) {
        return new String[] {
                obj.getSummary()
        };
    }
}
