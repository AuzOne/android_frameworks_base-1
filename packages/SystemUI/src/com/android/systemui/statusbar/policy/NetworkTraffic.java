package com.android.systemui.statusbar.policy;

import java.text.DecimalFormat;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;

public class NetworkTraffic extends TextView {
    public static final int MASK_UP = 0x00000001;
    public static final int MASK_DOWN = 0x00000002;
    public static final int MASK_UNIT = 0x00000004;
    public static final int MASK_PERIOD = 0xFFFF0000;

    private static final int KILOBIT = 1000;
    private static final int KILOBYTE = 1024;

    private static DecimalFormat decimalFormat = new DecimalFormat("##0.#");
    static {
        decimalFormat.setMaximumIntegerDigits(3);
        decimalFormat.setMaximumFractionDigits(1);
    }

    private boolean mAttached;
    private boolean mAutoHide;

    private int KB = KILOBIT;
    private int MB = KB * KB;
    private int GB = MB * KB;
    private int mAutoHideThreshold;
    private int mState = 0;
    private int txtSizeMulti;
    private int txtSizeSingle;

    private long lastUpdateTime;
    private long totalRxBytes;
    private long totalTxBytes;

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime;
            if (timeDelta < getInterval(mState) * .95) {
                if (msg.what != 1) return;
                if (timeDelta < 1) timeDelta = Long.MAX_VALUE;
            }
            lastUpdateTime = SystemClock.elapsedRealtime();

            long newTotalRxBytes = TrafficStats.getTotalRxBytes();
            long newTotalTxBytes = TrafficStats.getTotalTxBytes();
            long rxData = newTotalRxBytes - totalRxBytes;
            long txData = newTotalTxBytes - totalTxBytes;

            if (shouldHide(rxData, txData, timeDelta)) {
                setText("");
                setVisibility(View.GONE);
            } else {
                String symbol;
                if (KB == KILOBYTE) {
                    symbol = "B/s";
                } else {
                    symbol = "b/s";
                    rxData = rxData * 8;
                    txData = txData * 8;
                }

                String output = "";
                if (isSet(mState, MASK_UP)) output = formatOutput(timeDelta, txData, symbol);

                int textSize;
                if (isSet(mState, MASK_UP + MASK_DOWN)) {
                    output += "\n";
                    textSize = txtSizeMulti;
                } else {
                    textSize = txtSizeSingle;
                }

                if (isSet(mState, MASK_DOWN)) output += formatOutput(timeDelta, rxData, symbol);

                if (!output.contentEquals(getText())) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (float)textSize);
                    setText(output);
                }
                setVisibility(View.VISIBLE);
            }

            totalRxBytes = newTotalRxBytes;
            totalTxBytes = newTotalTxBytes;
            clearHandlerCallbacks();
            mTrafficHandler.postDelayed(mRunnable, getInterval(mState));
        }

        private String formatOutput(long timeDelta, long data, String symbol) {
            long speed = (long)(data / (timeDelta / 1000F));
            if (speed < KB) return decimalFormat.format(speed) + symbol;
            else if (speed < MB) return decimalFormat.format(speed / (float)KB) + 'k' + symbol;
            else if (speed < GB) return decimalFormat.format(speed / (float)MB) + 'M' + symbol;
            return decimalFormat.format(speed / (float)GB) + 'G' + symbol;
        }

        private boolean shouldHide(long rxData, long txData, long timeDelta) {
            long speedTxKB = (long)(txData / (timeDelta / 1000f)) / KILOBYTE;
            long speedRxKB = (long)(rxData / (timeDelta / 1000f)) / KILOBYTE;
            int mState = 2;
            return mAutoHide &&
                   (mState == MASK_DOWN && speedRxKB <= mAutoHideThreshold ||
                    mState == MASK_UP && speedTxKB <= mAutoHideThreshold ||
                    mState == MASK_UP + MASK_DOWN &&
                    speedRxKB <= mAutoHideThreshold &&
                    speedTxKB <= mAutoHideThreshold);
        }
    };

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTrafficHandler.sendEmptyMessage(0);
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NETWORK_TRAFFIC_STATE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NETWORK_TRAFFIC_AUTOHIDE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD), false, this, UserHandle.USER_ALL);
        }

        /**
         * @hide
         */
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    /**
     * @hide
     */
    public NetworkTraffic(Context context) {
        this(context, null);
    }

    /**
     * @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        txtSizeSingle = resources.getDimensionPixelSize(R.dimen.net_traffic_single_text_size);
        txtSizeMulti = resources.getDimensionPixelSize(R.dimen.net_traffic_multi_text_size);
        Handler mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) updateSettings();
        }
    };

    private boolean getConnectAvailable() {
        ConnectivityManager connManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = (connManager != null) ? connManager.getActiveNetworkInfo() : null;
        return network != null && network.isConnected();
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mAutoHide = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_AUTOHIDE, 0, UserHandle.USER_CURRENT) == 1;

        mAutoHideThreshold = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 10, UserHandle.USER_CURRENT);

        mState = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_STATE, 0, UserHandle.USER_CURRENT);

        if (isSet(mState, MASK_UNIT)) KB = KILOBYTE;
        else KB = KILOBIT;

        MB = KB * KB;
        GB = MB * KB;

        if (isSet(mState, MASK_UP) || isSet(mState, MASK_DOWN)) {
            if (getConnectAvailable()) {
                if (mAttached) {
                    totalRxBytes = TrafficStats.getTotalRxBytes();
                    lastUpdateTime = SystemClock.elapsedRealtime();
                    mTrafficHandler.sendEmptyMessage(1);
                }
                setVisibility(View.VISIBLE);
                updateTrafficDrawable();
                return;
            }
        } else {
            clearHandlerCallbacks();
        }
        setVisibility(View.GONE);
    }

    private static boolean isSet(int intState, int intMask) {
        return (intState & intMask) == intMask;
    }

    private static int getInterval(int intState) {
        int intInterval = intState >>> 16;
        return (intInterval >= 250 && intInterval <= 32750) ? intInterval : 1000;
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeCallbacks(mRunnable);
        mTrafficHandler.removeMessages(0);
        mTrafficHandler.removeMessages(1);
    }

    private void updateTrafficDrawable() {
        int intTrafficDrawable;
        if (isSet(mState, MASK_UP + MASK_DOWN)) intTrafficDrawable = R.drawable.stat_sys_network_traffic_updown;
        else if (isSet(mState, MASK_UP)) intTrafficDrawable = R.drawable.stat_sys_network_traffic_up;
        else if (isSet(mState, MASK_DOWN)) intTrafficDrawable = R.drawable.stat_sys_network_traffic_down;
        else intTrafficDrawable = 0;
        setCompoundDrawablesWithIntrinsicBounds(0, 0, intTrafficDrawable, 0);
    }
}
