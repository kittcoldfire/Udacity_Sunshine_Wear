/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatch extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatch.Engine> mWeakReference;

        public EngineHandler(SunshineWatch.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatch.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        //Paint mTextPaint;
        boolean mAmbient;
        Time mTime;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        Paint mSunBackground;
        Paint mTextTimePaint;
        Paint mTextDatePaint;
        Paint mTextDatePaintAmbient;
        Paint mTextHighPaint;
        Paint mTextLowPaint;
        Paint mTextLowPaintAmbient;

        float mTimeXOffset;
        float mTimeYOffset;
        float mDateXOffset;
        float mDateYOffset;
        float mDividerYOffset;
        float mTempYOffset;
        float mIconYOffset;

        String mHigh;
        String mLow;
        int mWeatherId;
        Bitmap weatherIcon;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(SunshineWatch.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        private String high, low;
        private int weatherId;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatch.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatch.this.getResources();

            mTimeYOffset = resources.getDimension(R.dimen.digital_text_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.date_text_y_offset);
            mDividerYOffset = resources.getDimension(R.dimen.divider_y_offset);
            mTempYOffset = resources.getDimension(R.dimen.temp_y_offset);
            mIconYOffset = resources.getDimension(R.dimen.icon_y_offset);

            mSunBackground = new Paint();
            mSunBackground.setColor(resources.getColor(R.color.primary));


            mTextTimePaint = createBoldTextPaint(resources.getColor(R.color.secondary_text));
            mTextDatePaint = createTextPaint(resources.getColor(R.color.primary_text));
            mTextHighPaint = createBoldTextPaint(resources.getColor(R.color.secondary_text));
            mTextDatePaintAmbient = createTextPaint(resources.getColor(R.color.white));
            mTextLowPaint = createTextPaint(resources.getColor(R.color.primary_text));
            mTextLowPaintAmbient = createTextPaint(resources.getColor(R.color.white));

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTime = new Time();
            calendar = Calendar.getInstance();
        }

        private Paint createBoldTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                googleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                if (googleApiClient != null && googleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(googleApiClient, this);
                    googleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatch.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatch.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatch.this.getResources();
            boolean isRound = insets.isRound();
            mTimeXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_text_x_offset_round : R.dimen.digital_text_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            mTextTimePaint.setTextSize(textSize);

            float dateSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            mTextDatePaint.setTextSize(dateSize);
            mTextDatePaintAmbient.setTextSize(dateSize);

            float tempSize = resources.getDimension(isRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);
            mTextHighPaint.setTextSize(tempSize);
            mTextLowPaint.setTextSize(tempSize);
            mTextLowPaintAmbient.setTextSize(tempSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private Calendar calendar;

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mSunBackground);
            }

            long timeNow = System.currentTimeMillis();
            calendar.setTimeInMillis(timeNow);

            boolean is24 = DateFormat.is24HourFormat(SunshineWatch.this);

            int minute = calendar.get(Calendar.MINUTE);
            int time  = calendar.get(Calendar.AM_PM);

            String timeText;
            if (is24) {
                int hour = calendar.get(Calendar.HOUR_OF_DAY);
                timeText = String.format("%02d:%02d", hour, minute);
            } else {
                int hour = calendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }

                String AM_PM = time == Calendar.AM ? "am" : "pm";

                timeText = String.format("%d:%02d %s", hour, minute, AM_PM);
            }

            float timeXOffset = mTextTimePaint.measureText(timeText) / 2;
            canvas.drawText(timeText, bounds.centerX() - timeXOffset, mTimeYOffset, mTextTimePaint);

            String format = "ccc, MMM d yyyy";
            SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.US);
            String dateText = sdf.format(timeNow);

            Paint datePaint = mAmbient ? mTextDatePaintAmbient : mTextDatePaint;
            float dateXOffset = mTextDatePaint.measureText(dateText) / 2;
            canvas.drawText(dateText, bounds.centerX() - dateXOffset, mDateYOffset, datePaint);

            //Draw the weather if we have it
            if(mHigh != null  && mLow != null) {
                // Create a divider line
                canvas.drawLine(bounds.centerX() - 20, mDividerYOffset, bounds.centerX() + 20, mDividerYOffset, mTextDatePaint);

                float highLength = mTextHighPaint.measureText(mHigh);
                if(mAmbient) {
                    float lowLength = mTextLowPaint.measureText(mLow);
                    float highXOffset = bounds.centerX() - ((highLength + lowLength + 20)) / 2; //center the high and low since no graphic
                    canvas.drawText(mHigh, highXOffset, mTempYOffset, mTextHighPaint);
                    canvas.drawText(mLow, highXOffset + highLength + 20, mTempYOffset, mTextLowPaintAmbient);
                } else {
                    float highXOffset = bounds.centerX() - (highLength / 2);
                    canvas.drawText(mHigh, highXOffset, mTempYOffset, mTextHighPaint);
                    canvas.drawText(mLow, bounds.centerX() + (highLength / 2) + 20, mTempYOffset, mTextLowPaint);
                    float iconXOffset = bounds.centerX() - ((highLength / 2) + weatherIcon.getWidth() + 30);
                    canvas.drawBitmap(weatherIcon, iconXOffset, mIconYOffset - weatherIcon.getHeight(), null);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(googleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(SunshineWatch.this.getPackageName(), "On data changed");
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.d("SunshineWatch", path);
                    if (path.equals("/weather-data")) {
                        if (dataMap.containsKey("high")) {
                            mHigh = dataMap.getString("high");
                        }
                        if (dataMap.containsKey("low")) {
                            mLow = dataMap.getString("low");
                        }
                        if (dataMap.containsKey("weatherId")) {
                            mWeatherId = dataMap.getInt("weatherId");

                            if (mWeatherId != 0) {

                                Drawable b = getDrawable(Utility.getIconResourceForWeatherCondition(mWeatherId));
                                Bitmap icon = ((BitmapDrawable) b).getBitmap();
                                float scaledWidth = (mTextHighPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                                weatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) mTextHighPaint.getTextSize(), true);
                            }
                        }

                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }
    }
}