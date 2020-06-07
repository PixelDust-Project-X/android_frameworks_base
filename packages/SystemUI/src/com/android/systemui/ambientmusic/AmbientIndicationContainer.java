package com.android.systemui.ambientmusic;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.AutoReinflateContainer;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.phone.StatusBar;

import com.android.systemui.ambientmusic.AmbientIndicationInflateListener;

import java.util.concurrent.TimeUnit;

public class AmbientIndicationContainer extends AutoReinflateContainer implements
        NotificationMediaManager.MediaListener {

    public static final boolean DEBUG_AMBIENTMUSIC = true;

    private View mAmbientIndication;
    private ImageView mIcon;
    private CharSequence mIndication;
    private StatusBar mStatusBar;
    private TextView mText;
    private TextView mTrackLength;
    private Context mContext;
    protected MediaMetadata mMediaMetaData;
    private String mMediaText;
    private boolean mForcedMediaDoze;
    private Handler mHandler;
    private boolean mInfoAvailable;
    private String mInfoToSet;
    private String mLengthInfo;
    private boolean mDozing;
    private String mLastInfo;

    private boolean mNpInfoAvailable;

    protected NotificationMediaManager mMediaManager;

    public AmbientIndicationContainer(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        initDependencies();
        mContext = context;
    }

    public void hideIndication() {
        setIndication(null, null, false);
    }

    public void initializeView(StatusBar statusBar, Handler handler) {
        mStatusBar = statusBar;
        addInflateListener(new AmbientIndicationInflateListener(this));
        mHandler = handler;
        if (DEBUG_AMBIENTMUSIC) {
            Log.d("AmbientIndicationContainer", "initializeView");
        }
    }

    public void updateAmbientIndicationView(View view) {
        mAmbientIndication = findViewById(R.id.ambient_indication);
        mText = (TextView)findViewById(R.id.ambient_indication_text);
        mTrackLength = (TextView)findViewById(R.id.ambient_indication_track_length);
        mIcon = (ImageView)findViewById(R.id.ambient_indication_icon);
        setIndication(mMediaMetaData, mMediaText, false);
        if (DEBUG_AMBIENTMUSIC) {
            Log.d("AmbientIndicationContainer", "updateAmbientIndicationView");
        }
    }

    public void initDependencies() {
        mMediaManager = Dependency.get(NotificationMediaManager.class);
        mMediaManager.addCallback(this);
    }

    public void setDozing(boolean dozing) {
        if (dozing == mDozing) return;

        mDozing = dozing;
        setTickerMarquee(dozing, false);
        if (dozing && (mInfoAvailable || mNpInfoAvailable)) {
            mText.setText(mInfoToSet);
            mLastInfo = mInfoToSet;
            mTrackLength.setText(mLengthInfo);
            mAmbientIndication.setVisibility(View.VISIBLE);
            updatePosition();
        } else {
            setCleanLayout(-1);
            mAmbientIndication.setVisibility(View.INVISIBLE);
            mText.setText(null);
            mTrackLength.setText(null);
        }
    }

    private void setTickerMarquee(boolean enable, boolean extendPulseOnNewTrack) {
        if (enable) {
            setTickerMarquee(false, false);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mText.setEllipsize(TruncateAt.MARQUEE);
                    mText.setMarqueeRepeatLimit(2);
                    mText.setSelected(true);
                    if (extendPulseOnNewTrack && mStatusBar.isPulsing()) {
                        mStatusBar.getDozeScrimController().extendPulseForMusicTicker();
                    }
                }
            }, 1600);
        } else {
            mText.setEllipsize(null);
            mText.setSelected(false);
        }
    }

    public void setOnPulseEvent(int reason, boolean pulsing) {
        setCleanLayout(reason);
        setTickerMarquee(pulsing,
                reason == DozeLog.PULSE_REASON_FORCED_MEDIA_NOTIFICATION);
    }

    public void setCleanLayout(int reason) {
        mForcedMediaDoze =
                reason == DozeLog.PULSE_REASON_FORCED_MEDIA_NOTIFICATION;
        updatePosition();
        if (DEBUG_AMBIENTMUSIC) {
            Log.d("AmbientIndicationContainer", "setCleanLayout, reason=" + reason);
        }
    }

    public void updatePosition() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) this.getLayoutParams();
        lp.gravity = mForcedMediaDoze ? Gravity.CENTER : Gravity.BOTTOM;
        this.setLayoutParams(lp);
    }

    private void setNowPlayingIndication(String trackInfo) {
        // don't trigger this if we are already showing local/remote session track info
        setIndication(null, trackInfo, true);
    }

    public void setIndication(MediaMetadata mediaMetaData, String notificationText, boolean nowPlaying) {
        // never override local music ticker
        if (nowPlaying && mInfoAvailable || mAmbientIndication == null) return;
        CharSequence charSequence = null;
        mLengthInfo = null;
        mInfoToSet = null;
        if (mediaMetaData != null) {
            CharSequence artist = mediaMetaData.getText(MediaMetadata.METADATA_KEY_ARTIST);
            CharSequence album = mediaMetaData.getText(MediaMetadata.METADATA_KEY_ALBUM);
            CharSequence title = mediaMetaData.getText(MediaMetadata.METADATA_KEY_TITLE);
            long duration = mediaMetaData.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (artist != null && title != null) {
                /* considering we are in Ambient mode here, it's not worth it to show
                    too many infos, so let's skip album name to keep a smaller text */
                charSequence = artist.toString() + " - " + title.toString();
                if (duration != 0) {
                    mLengthInfo = String.format("%02d:%02d",
                            TimeUnit.MILLISECONDS.toMinutes(duration),
                            TimeUnit.MILLISECONDS.toSeconds(duration) -
                            TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(duration))).toString();
                }
            }
        }
        if (mDozing) {
            // if we are already showing an Ambient Notification with track info,
            // stop the current scrolling and start it delayed again for the next song
            setTickerMarquee(true, true);
        }

        if (!TextUtils.isEmpty(charSequence)) {
            mInfoToSet = charSequence.toString();
        } else if (!TextUtils.isEmpty(notificationText)) {
            mInfoToSet = notificationText;
            mLengthInfo = null;
        }

        if (nowPlaying) {
            mNpInfoAvailable = mInfoToSet != null;
        } else {
            mInfoAvailable = mInfoToSet != null;
        }

        if (mInfoAvailable || mNpInfoAvailable) {
            mMediaMetaData = mediaMetaData;
            mMediaText = notificationText;
            boolean isAnotherTrack = (mInfoAvailable || mNpInfoAvailable)
                    && (TextUtils.isEmpty(mLastInfo) || (!TextUtils.isEmpty(mLastInfo) && !mLastInfo.equals(mInfoToSet)));
            if (!DozeParameters.getInstance(mContext).getAlwaysOn() && mStatusBar != null && isAnotherTrack) {
                mStatusBar.triggerAmbientForMedia();
            }
            if (mDozing) {
                mLastInfo = mInfoToSet;
            }
        }
        if (mInfoToSet != null) mText.setText(mInfoToSet);
        if (mLengthInfo != null) mTrackLength.setText(mLengthInfo);
        mAmbientIndication.setVisibility(mDozing && (mInfoAvailable || mNpInfoAvailable) ? View.VISIBLE : View.INVISIBLE);
    }

    public View getIndication() {
        return mAmbientIndication;
    }

    @Override
    public void onMetadataOrStateChanged(MediaMetadata metadata, @PlaybackState.State int state) {
        synchronized (this) {
            mMediaMetaData = metadata;
        }
        if (mMediaManager.getNowPlayingTrack() != null) {
            setNowPlayingIndication(mMediaManager.getNowPlayingTrack());
            if (DEBUG_AMBIENTMUSIC) {
                Log.d("AmbientIndicationContainer", "onMetadataOrStateChanged: Now Playing: track=" + mMediaManager.getNowPlayingTrack());
            }
        } else {
            setIndication(mMediaMetaData, null, false); //2nd param must be null here
        }
        if (DEBUG_AMBIENTMUSIC) {
            CharSequence artist = "artist";
            CharSequence album = "album";
            CharSequence title = "title";
            if (mMediaMetaData != null) {
                artist = mMediaMetaData.getText(MediaMetadata.METADATA_KEY_ARTIST);
                album = mMediaMetaData.getText(MediaMetadata.METADATA_KEY_ALBUM);
                title = mMediaMetaData.getText(MediaMetadata.METADATA_KEY_TITLE);
                Log.d("AmbientIndicationContainer", "onMetadataOrStateChanged: Music Ticker: artist=" + artist + "; album="+ album + "; title=" + title);
            }
        }
    }
}
