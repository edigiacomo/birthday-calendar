/*
 * Copyright (C) 2010 Sam Steele
 * Copyright (C) 2012 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This file is part of Birthday Adapter.
 * 
 * Birthday Adapter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Birthday Adapter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Birthday Adapter.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.birthdayadapter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.birthdayadapter.util.Constants;
import org.birthdayadapter.util.PreferencesHelper;

import android.accounts.Account;
import android.accounts.OperationCanceledException;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import android.provider.ContactsContract;
import android.util.Log;

public class CalendarSyncAdapterService extends Service {
    private static SyncAdapterImpl sSyncAdapter = null;

    private static String CALENDAR_COLUMN_NAME = "birthdays";

    public CalendarSyncAdapterService() {
        super();
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        private Context mContext;

        public SyncAdapterImpl(Context context) {
            super(context, true);
            mContext = context;
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority,
                ContentProviderClient provider, SyncResult syncResult) {
            try {
                CalendarSyncAdapterService.performSync(mContext, account, extras, authority,
                        provider, syncResult);
            } catch (OperationCanceledException e) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        IBinder ret = null;
        ret = getSyncAdapter().getSyncAdapterBinder();
        return ret;
    }

    private SyncAdapterImpl getSyncAdapter() {
        if (sSyncAdapter == null)
            sSyncAdapter = new SyncAdapterImpl(this);
        return sSyncAdapter;
    }

    /**
     * Updates calendar color
     * 
     * @param context
     * @param color
     */
    public static void updateCalendarColor(Context context, int color) {
        ContentResolver contentResolver = context.getContentResolver();

        Uri uri = ContentUris.withAppendedId(getBirthdayAdapterUri(Calendars.CONTENT_URI),
                getCalendar(context));

        Log.d(Constants.TAG, "Updating calendar color to " + color + " with uri " + uri.toString());

        ContentProviderClient client = contentResolver
                .acquireContentProviderClient(CalendarContract.AUTHORITY);

        ContentValues values = new ContentValues();
        values.put(Calendars.CALENDAR_COLOR, color);
        try {
            client.update(uri, values, null, null);
        } catch (RemoteException e) {
            Log.e(Constants.TAG, "Error while updating calendar color!");
            e.printStackTrace();
        }
        client.release();
    }

    /**
     * Gets calendar id, when no calendar is present, create one!
     * 
     * @param context
     * @return
     */
    private static long getCalendar(Context context) {
        ContentResolver contentResolver = context.getContentResolver();

        Log.d(Constants.TAG, "get calendar!");
        // Find the calendar if we've got one
        Uri calenderUri = getBirthdayAdapterUri(Calendars.CONTENT_URI);

        Cursor c1 = contentResolver.query(calenderUri, new String[] { BaseColumns._ID }, null,
                null, null);
        if (c1.moveToNext()) {
            return c1.getLong(0);
        } else {
            ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();

            ContentProviderOperation.Builder builder = ContentProviderOperation
                    .newInsert(getBirthdayAdapterUri(Calendars.CONTENT_URI));
            builder.withValue(Calendars.ACCOUNT_NAME, Constants.ACCOUNT_NAME);
            builder.withValue(Calendars.ACCOUNT_TYPE, Constants.ACCOUNT_TYPE);
            builder.withValue(Calendars.NAME, CALENDAR_COLUMN_NAME);
            builder.withValue(Calendars.CALENDAR_DISPLAY_NAME,
                    context.getString(R.string.calendar_display_name));
            builder.withValue(Calendars.CALENDAR_COLOR, PreferencesHelper.getColor(context));
            builder.withValue(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ);
            builder.withValue(Calendars.OWNER_ACCOUNT, Constants.ACCOUNT_NAME);
            builder.withValue(Calendars.SYNC_EVENTS, 1);
            operationList.add(builder.build());
            try {
                contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
            } catch (Exception e) {
                Log.e(Constants.TAG, "Error: " + e.getMessage());
                e.printStackTrace();
                return -1;
            }
            return getCalendar(context);
        }
    }

    /**
     * Set new minutes to all reminders in birthday calendar. minutesBefore=-1 will delete all
     * reminders!
     * 
     * @param context
     * @param minutesBefore
     */
    public static void updateAllReminders(Context context, int minutesBefore) {
        ContentResolver contentResolver = context.getContentResolver();

        Uri remindersUri = getBirthdayAdapterUri(Reminders.CONTENT_URI);

        Cursor cursor = contentResolver.query(remindersUri, new String[] { Reminders._ID,
                Reminders.MINUTES }, null, null, null);

        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();

        while (cursor.moveToNext()) {
            Uri uri = ContentUris.withAppendedId(remindersUri,
                    cursor.getLong(cursor.getColumnIndex(Reminders._ID)));

            ContentProviderOperation.Builder builder;
            if (minutesBefore >= 1) {
                Log.d(Constants.TAG, "Updating reminder minutes to " + minutesBefore + " with uri "
                        + uri.toString());

                builder = ContentProviderOperation.newUpdate(uri);

                builder.withValue(Reminders.MINUTES, minutesBefore);
            } else {
                Log.d(Constants.TAG, "Disable reminder with uri " + uri.toString());

                builder = ContentProviderOperation.newDelete(uri);
            }

            operationList.add(builder.build());
        }

        try {
            contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
        } catch (Exception e) {
            Log.e(Constants.TAG, "Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Builds URI for Birthday Adapter based on account
     * 
     * @param uri
     * @return
     */
    public static Uri getBirthdayAdapterUri(Uri uri) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, Constants.ACCOUNT_NAME)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, Constants.ACCOUNT_TYPE).build();
    }

    /**
     * Get a new ContentProviderOperation to insert a event
     * 
     * @param context
     * @param calendarId
     * @param eventDate
     * @param title
     * @return
     */
    private static ContentProviderOperation insertEvent(Context context, long calendarId,
            Date eventDate, String title) {
        ContentProviderOperation.Builder builder;

        builder = ContentProviderOperation.newInsert(getBirthdayAdapterUri(Events.CONTENT_URI));
        // builder.withValue(Events._SYNC_ID, Long.valueOf(newEventId));

        Calendar cal = Calendar.getInstance();
        cal.setTime(eventDate);
        cal.set(Calendar.HOUR, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // allday events have to be set in UTC!
        // without UTC it results in:
        // CalendarProvider2 W insertInTransaction: allDay is true but sec, min, hour were not 0.
        // http://stackoverflow.com/questions/3440172/getting-exception-when-inserting-events-in-android-calendar
        cal.setTimeZone(TimeZone.getTimeZone("UTC"));

        long dtstart = cal.getTimeInMillis();

        builder.withValue(Events.CALENDAR_ID, calendarId);
        builder.withValue(Events.DTSTART, dtstart);
        builder.withValue(Events.TITLE, title);

        builder.withValue(Events.ALL_DAY, 1);

        // Duration: 1 hour
        // without:
        // CalendarProvider2 E Repeating event has no duration -- should not happen.
        builder.withValue(Events.DURATION, "PT1H");

        // repeat rule: every year
        builder.withValue(Events.RRULE, "FREQ=YEARLY");

        builder.withValue(Events.STATUS, Events.STATUS_CONFIRMED);
        return builder.build();
    }

    /**
     * Gets ContentProviderOperation to insert new reminder to the ContentProviderOperation with the
     * given backRef. This is done using "withValueBackReference"
     * 
     * @param context
     * @param backRef
     * @return
     */
    private static ContentProviderOperation insertReminder(Context context, int backRef) {
        ContentProviderOperation.Builder builder;

        builder = ContentProviderOperation.newInsert(getBirthdayAdapterUri(Reminders.CONTENT_URI));

        // add reminder to lastly added event
        // see http://stackoverflow.com/questions/4655291/semantics-of-withvaluebackreference
        builder.withValueBackReference(Reminders.EVENT_ID, backRef);
        builder.withValue(Reminders.MINUTES, 10);
        builder.withValue(Reminders.METHOD, Reminders.METHOD_ALERT);

        return builder.build();
    }

    /**
     * The date format in the contact events is not standardized! See
     * http://dmfs.org/carddav/?date_format . This method will try to parse it by first using
     * yyyy-MM-dd, then yyyyMMdd and then timestamp.
     * 
     * @param eventDateString
     * @return eventDate as Date object
     */
    private static Date parseEventDateString(String eventDateString) {
        SimpleDateFormat dateFormat1 = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dateFormat1.setTimeZone(TimeZone.getDefault());
        SimpleDateFormat dateFormat2 = new SimpleDateFormat("yyyyMMdd", Locale.US);
        dateFormat2.setTimeZone(TimeZone.getDefault());

        Date eventDate = null;

        try {
            eventDate = dateFormat1.parse(eventDateString);

        } catch (ParseException e) {
            Log.e(Constants.TAG, "Event Date String " + eventDateString
                    + " could not be parsed with yyyy-MM-dd! Falling back to yyyyMMdd!");
            try {
                eventDate = dateFormat2.parse(eventDateString);

            } catch (ParseException e2) {
                Log.e(Constants.TAG, "Event Date String " + eventDateString
                        + " could not be parsed with yyyyMMdd! Falling back to timestamp!");
                try {
                    eventDate = new Date(Long.parseLong(eventDateString));

                } catch (NumberFormatException e3) {
                    Log.e(Constants.TAG, "Event Date String " + eventDateString
                            + " could not be parsed as a timestamp! Parsing failed!");

                    eventDate = null;
                }
            }
        }

        return eventDate;
    }

    /**
     * Get Cursor with name, contact id, date of event, and type columns
     * 
     * http://stackoverflow.com/questions/8579883/get-birthday-for-each-contact-in-android-
     * application
     * 
     * @return
     */
    private static Cursor getContactsEvents(Context context) {
        Uri uri = ContactsContract.Data.CONTENT_URI;

        String[] projection = new String[] { ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Event.CONTACT_ID,
                ContactsContract.CommonDataKinds.Event.START_DATE,
                ContactsContract.CommonDataKinds.Event.TYPE,
                ContactsContract.CommonDataKinds.Event.LABEL };

        String where = ContactsContract.Data.MIMETYPE + "= ? AND "
                + ContactsContract.CommonDataKinds.Event.TYPE + " IS NOT NULL";
        String[] selectionArgs = new String[] { ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE };
        String sortOrder = null;

        return context.getContentResolver().query(uri, projection, where, selectionArgs, sortOrder);
    }

    private static void performSync(Context context, Account account, Bundle extras,
            String authority, ContentProviderClient provider, SyncResult syncResult)
            throws OperationCanceledException {
        ContentResolver contentResolver = context.getContentResolver();

        long calendarId = getCalendar(context);
        if (calendarId == -1) {
            Log.e("CalendarSyncAdapter", "Unable to create calendar");
            return;
        }

        // Okay, now this works as follows:
        // 1. Clear events table for this account completely
        // 2. Get birthdays from contacts
        // 3. Create event for each birthday

        // Known limitations:
        // - I am not doing any updating, just delete everything and then recreate everything
        // - birthdays may be stored in other ways on some phones
        // see
        // http://stackoverflow.com/questions/8579883/get-birthday-for-each-contact-in-android-application

        // clear table with workaround: "_id != -1"
        int delRows = contentResolver.delete(getBirthdayAdapterUri(Events.CONTENT_URI),
                "_id != -1", null);
        Log.i(Constants.TAG, "Birthdays calendar is now cleared, deleted " + delRows + " rows!");

        // collection of birthdays that will later be added to the calendar
        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();

        // iterate through all Contact Events
        Cursor cursor = getContactsEvents(context);
        int eventDateColumn = cursor
                .getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE);
        int displayNameColumn = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
        int eventTypeColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.TYPE);
        int eventCustomLabelColumn = cursor
                .getColumnIndex(ContactsContract.CommonDataKinds.Event.LABEL);

        int backRef = 0;
        while (cursor.moveToNext()) {
            String eventDateString = cursor.getString(eventDateColumn);
            String displayName = cursor.getString(displayNameColumn);
            int eventType = cursor.getInt(eventTypeColumn);

            Date eventDate = parseEventDateString(eventDateString);

            String title = null;
            switch (eventType) {
            case ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM:
                String eventCustomLabel = cursor.getString(eventCustomLabelColumn);

                title = String.format(context.getString(R.string.event_title_custom),
                        eventCustomLabel, displayName);
                break;
            case ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY:
                title = String.format(context.getString(R.string.event_title_anniversary),
                        displayName);
                break;
            case ContactsContract.CommonDataKinds.Event.TYPE_OTHER:
                title = String.format(context.getString(R.string.event_title_other), displayName);
                break;
            case ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY:
                title = String
                        .format(context.getString(R.string.event_title_birthday), displayName);
                break;
            default:
                title = String.format(context.getString(R.string.event_title_other), displayName);
                break;
            }

            if (eventDate != null) {
                Log.d(Constants.TAG, "BackRef is " + backRef);

                operationList.add(insertEvent(context, calendarId, eventDate, title));
                operationList.add(insertReminder(context, backRef));
                backRef += 2;
            }

        }

        /* Create events */
        if (operationList.size() > 0) {
            try {
                Log.d(Constants.TAG, "Start applying the batch...");
                contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
                Log.d(Constants.TAG, "Applying the batch was successful!");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}