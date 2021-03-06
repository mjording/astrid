/**
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.alarms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;

import com.timsu.astrid.R;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.astrid.api.AstridApiConstants;
import com.todoroo.astrid.api.DetailExposer;
import com.todoroo.astrid.model.Metadata;

/**
 * Exposes Task Detail for tags, i.e. "Tags: frogs, animals"
 *
 * @author Tim Su <tim@todoroo.com>
 *
 */
public class AlarmDetailExposer extends BroadcastReceiver implements DetailExposer {

    @Override
    public void onReceive(Context context, Intent intent) {
        // get tags associated with this task
        long taskId = intent.getLongExtra(AstridApiConstants.EXTRAS_TASK_ID, -1);
        if(taskId == -1)
            return;

        boolean extended = intent.getBooleanExtra(AstridApiConstants.EXTRAS_EXTENDED, false);
        String taskDetail = getTaskDetails(context, taskId, extended);
        if(taskDetail == null)
            return;

        // transmit
        Intent broadcastIntent = new Intent(AstridApiConstants.BROADCAST_SEND_DETAILS);
        broadcastIntent.putExtra(AstridApiConstants.EXTRAS_ADDON, AlarmService.IDENTIFIER);
        broadcastIntent.putExtra(AstridApiConstants.EXTRAS_RESPONSE, taskDetail);
        broadcastIntent.putExtra(AstridApiConstants.EXTRAS_EXTENDED, extended);
        broadcastIntent.putExtra(AstridApiConstants.EXTRAS_TASK_ID, taskId);
        context.sendBroadcast(broadcastIntent, AstridApiConstants.PERMISSION_READ);
    }

    @Override
    public String getTaskDetails(Context context, long id, boolean extended) {
        if(extended)
            return null;

        TodorooCursor<Metadata> cursor = AlarmService.getInstance().getAlarms(id);
        long nextTime = -1;
        try {
            for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                long time = cursor.get(Alarm.TIME);
                if(time > DateUtilities.now()) {
                    nextTime = time;
                    break;
                }
            }

            if(nextTime == -1)
                return null;
            CharSequence durationString = DateUtils.getRelativeDateTimeString(context,
                    nextTime, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_ALL);
            return context.getString(R.string.alarm_ADE_detail, durationString);
        } finally {
            cursor.close();
        }
    }

    @Override
    public String getPluginIdentifier() {
        return AlarmService.IDENTIFIER;
    }

}
