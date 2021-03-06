package com.todoroo.astrid.backup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.xmlpull.v1.XmlSerializer;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.util.Xml;
import android.widget.Toast;

import com.timsu.astrid.R;
import com.todoroo.andlib.data.AbstractModel;
import com.todoroo.andlib.data.Property;
import com.todoroo.andlib.data.Property.PropertyVisitor;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.ExceptionService;
import com.todoroo.andlib.sql.Order;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.astrid.core.PluginServices;
import com.todoroo.astrid.dao.MetadataDao.MetadataCriteria;
import com.todoroo.astrid.model.Metadata;
import com.todoroo.astrid.model.Task;
import com.todoroo.astrid.service.MetadataService;
import com.todoroo.astrid.service.TaskService;
import com.todoroo.astrid.utility.Preferences;

public class TasksXmlExporter {

    // --- public interface

    /**
     * Import tasks from the given file
     *
     * @param input
     * @param runAfterExport
     */
    public static void exportTasks(Context context, boolean isService, Runnable runAfterExport) {
        new TasksXmlExporter(context, isService, runAfterExport);
    }

    // --- implementation

    private static final int FORMAT = 2;

    private final Context context;
    private int exportCount;
    private XmlSerializer xml;
    private final TaskService taskService = PluginServices.getTaskService();
    private final MetadataService metadataService = PluginServices.getMetadataService();
    private final ExceptionService exceptionService = PluginServices.getExceptionService();

    private final ProgressDialog progressDialog;
    private final Handler handler;

    private void setProgress(final int taskNumber, final int total) {
        handler.post(new Runnable() {
            public void run() {
                progressDialog.setMax(total);
                progressDialog.setProgress(taskNumber);
            }
        });
    }

    private TasksXmlExporter(final Context context, final boolean isService,
            final Runnable runAfterExport) {
        this.context = context;
        this.exportCount = 0;

        handler = new Handler();
        progressDialog = new ProgressDialog(context);
        if(!isService) {
            progressDialog.setIcon(android.R.drawable.ic_dialog_info);
            progressDialog.setTitle(R.string.export_progress_title);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setProgress(0);
            progressDialog.setCancelable(false);
            progressDialog.setIndeterminate(false);
            progressDialog.show();
            if(context instanceof Activity)
                progressDialog.setOwnerActivity((Activity)context);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String output = setupFile(BackupConstants.getExportDirectory(),
                            isService);
                    doTasksExport(output);
                    Preferences.setLong(BackupPreferences.PREF_BACKUP_LAST_DATE,
                            DateUtilities.now());
                    Preferences.setString(BackupPreferences.PREF_BACKUP_LAST_ERROR, null);

                    if (!isService)
                        onFinishExport(output);
                } catch (IOException e) {
                    if(!isService)
                        exceptionService.displayAndReportError(context,
                            context.getString(R.string.backup_TXI_error), e);
                    else {
                        exceptionService.reportError("background-backup", e); //$NON-NLS-1$
                        Preferences.setString(BackupPreferences.PREF_BACKUP_LAST_ERROR, e.toString());
                    }
                } finally {
                    if(runAfterExport != null)
                        runAfterExport.run();
                }
            }
        }).start();
    }


    @SuppressWarnings("nls")
    private void doTasksExport(String output) throws IOException {
        File xmlFile = new File(output);
        xmlFile.createNewFile();
        FileOutputStream fos = new FileOutputStream(xmlFile);
        xml = Xml.newSerializer();
        xml.setOutput(fos, BackupConstants.XML_ENCODING);

        xml.startDocument(null, null);
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

        xml.startTag(null, BackupConstants.ASTRID_TAG);
        xml.attribute(null, BackupConstants.ASTRID_ATTR_VERSION,
                Integer.toString(Preferences.getCurrentVersion()));
        xml.attribute(null, BackupConstants.ASTRID_ATTR_FORMAT,
                Integer.toString(FORMAT));

        serializeTasks();

        xml.endTag(null, BackupConstants.ASTRID_TAG);
        xml.endDocument();
        xml.flush();
        fos.close();
    }

    private void serializeTasks() throws IOException {
        TodorooCursor<Task> cursor = taskService.query(Query.select(
                Task.PROPERTIES).orderBy(Order.asc(Task.ID)));
        try {
            Task task = new Task();
            int length = cursor.getCount();
            for(int i = 0; i < length; i++) {
                cursor.moveToNext();
                task.readFromCursor(cursor);

                setProgress(i, length);

                xml.startTag(null, BackupConstants.TASK_TAG);
                serializeModel(task, Task.PROPERTIES, Task.ID);
                serializeMetadata(task);
                xml.endTag(null, BackupConstants.TASK_TAG);
                this.exportCount++;
            }
        } finally {
            cursor.close();
        }
    }

    private void serializeMetadata(Task task) throws IOException {
        TodorooCursor<Metadata> cursor = metadataService.query(Query.select(
                Metadata.PROPERTIES).where(MetadataCriteria.byTask(task.getId())));
        try {
            Metadata metadata = new Metadata();
            for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                metadata.readFromCursor(cursor);

                xml.startTag(null, BackupConstants.METADATA_TAG);
                serializeModel(metadata, Metadata.PROPERTIES, Metadata.ID, Metadata.TASK);
                xml.endTag(null, BackupConstants.METADATA_TAG);
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Turn a model into xml attributes
     * @param model
     */
    private void serializeModel(AbstractModel model, Property<?>[] properties, Property<?>... excludes) {
        outer: for(Property<?> property : properties) {
            for(Property<?> exclude : excludes)
                if(property.name.equals(exclude.name))
                    continue outer;

            try {
                property.accept(xmlWritingVisitor, model);
            } catch (Exception e) {
                Log.e("astrid-exporter", //$NON-NLS-1$
                        "Caught exception while reading " + property.name + //$NON-NLS-1$
                        " from " + model.getDatabaseValues(), e); //$NON-NLS-1$
            }
        }
    }

    private final XmlWritingPropertyVisitor xmlWritingVisitor = new XmlWritingPropertyVisitor();

    private class XmlWritingPropertyVisitor implements PropertyVisitor<Void, AbstractModel> {
        @Override
        public Void visitInteger(Property<Integer> property, AbstractModel data) {
            try {
                xml.attribute(null, property.name, data.getValue(property).toString());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(e);
            } catch (IllegalStateException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        @Override
        public Void visitLong(Property<Long> property, AbstractModel data) {
            try {
                xml.attribute(null, property.name, data.getValue(property).toString());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(e);
            } catch (IllegalStateException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        @Override
        public Void visitDouble(Property<Double> property, AbstractModel data) {
            try {
                xml.attribute(null, property.name, data.getValue(property).toString());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(e);
            } catch (IllegalStateException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        @Override
        public Void visitString(Property<String> property, AbstractModel data) {
            try {
                String value = data.getValue(property);
                if(value == null)
                    return null;
                xml.attribute(null, property.name, value);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(e);
            } catch (IllegalStateException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

    }

    private void onFinishExport(final String outputFile) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                CharSequence text = String.format(context.getString(R.string.export_toast),
                        context.getResources().getQuantityString(R.plurals.Ntasks, exportCount,
                                exportCount), outputFile);
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
                if(progressDialog.isShowing())
                    progressDialog.dismiss();
            }
        });
    }

    /**
     * Creates directories if necessary and returns fully qualified file
     * @param directory
     * @return output file name
     * @throws IOException
     */
    private String setupFile(File directory, boolean isService) throws IOException {
        File astridDir = directory;
        if (astridDir != null) {
            // Check for /sdcard/astrid directory. If it doesn't exist, make it.
            if (astridDir.exists() || astridDir.mkdir()) {
                String fileName;
                if (isService) {
                    fileName = BackupConstants.BACKUP_FILE_NAME;
                } else {
                    fileName = BackupConstants.EXPORT_FILE_NAME;
                }
                fileName = String.format(fileName, BackupDateUtilities.getDateForExport());
                return astridDir.getAbsolutePath() + File.separator + fileName;
            } else {
                // Unable to make the /sdcard/astrid directory.
                throw new IOException(context.getString(R.string.DLG_error_sdcard,
                        astridDir.getAbsolutePath()));
            }
        } else {
            // Unable to access the sdcard because it's not in the mounted state.
            throw new IOException(context.getString(R.string.DLG_error_sdcard_general));
        }
    }

}
