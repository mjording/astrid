/*
 * Copyright (c) 2009, Todoroo Inc
 * All Rights Reserved
 * http://www.todoroo.com
 */
package com.todoroo.astrid.legacy;

import com.todoroo.andlib.data.AbstractDatabase;
import com.todoroo.andlib.data.GenericDao;
import com.todoroo.andlib.data.Table;

/**
 * Database wrapper
 *
 * @author Tim Su <tim@todoroo.com>
 *
 */
@SuppressWarnings({"nls","deprecation"})
public class AlarmDatabase extends AbstractDatabase {

    // --- constants

    /**
     * Database version number. This variable must be updated when database
     * tables are updated, as it determines whether a database needs updating.
     */
    public static final int VERSION = 1;

    /**
     * Database name (must be unique)
     */
    public static final String NAME = "alarms";

    /**
     * List of table/ If you're adding a new table, add it to this list and
     * also make sure that our SQLite helper does the right thing.
     */
    public static final Table[] TABLES =  new Table[] {
        TransitionalAlarm.TABLE
    };

    // --- implementation

    private final GenericDao<TransitionalAlarm> dao = new GenericDao<TransitionalAlarm>(TransitionalAlarm.class, this);

    @Override
    protected String getName() {
        return NAME;
    }

    @Override
    protected int getVersion() {
        return VERSION;
    }

    @Override
    public Table[] getTables() {
        return TABLES;
    }

    public GenericDao<TransitionalAlarm> getDao() {
        return dao;
    }

    @Override
    protected synchronized void onCreateTables() {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE INDEX IF NOT EXISTS a_task ON ").
            append(TransitionalAlarm.TABLE).append('(').
                append(TransitionalAlarm.TASK.name).
            append(')');
        database.execSQL(sql.toString());
    }

    @Override
    protected boolean onUpgrade(int oldVersion, int newVersion) {
        return false;
    }

}

