package com.kaibo.music.player.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.session.PlaybackStateCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.widget.RemoteViews;

import com.kaibo.music.bean.PlayListBean;
import com.kaibo.music.bean.SongBean;
import com.kaibo.music.database.PlayListHelper;
import com.kaibo.music.player.Constants;
import com.kaibo.music.player.R;
import com.kaibo.music.player.engine.MusicPlayerEngine;
import com.kaibo.music.player.handler.MusicPlayerHandler;
import com.kaibo.music.player.manager.AudioAndFocusManager;
import com.kaibo.music.player.manager.FloatLyricViewManager;
import com.kaibo.music.player.manager.MediaSessionManager;
import com.kaibo.music.player.manager.PlayModeManager;
import com.kaibo.music.utils.DownLoadManager;
import com.kaibo.music.utils.SPUtils;
import com.kaibo.music.utils.SystemUtils;
import com.orhanobut.logger.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

/**
 * 音乐播放后台服务
 */
public class MusicPlayerService extends Service {

    private static final String TAG = "MusicPlayerService";

    /**
     * 播放队列
     */
    private List<SongBean> mPlayQueue = new ArrayList<>();

    /**
     * 记录播放位置
     */
    private int mPlayingPos = -1;

    /**
     * 当前所播放的歌单Id
     */
    private String mPlaylistId = PlayListBean.PLAYLIST_QUEUE_ID;

    //广播接收者
    private ServiceReceiver mServiceReceiver;

    private HeadsetReceiver mHeadsetReceiver;

    private HeadsetPlugInReceiver mHeadsetPlugInReceiver;

    /**
     * 桌面歌词管理
     */
    private FloatLyricViewManager mFloatLyricViewManager;
    /**
     * 线控和蓝牙远程控制播放
     */
    private MediaSessionManager mediaSessionManager;
    /**
     * 管理音频焦点
     */
    private AudioAndFocusManager audioAndFocusManager;

    /**
     * 通知
     */
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mNotificationBuilder;
    private Notification mNotification;

    /**
     * 进程间通信
     */
    private IMusicServiceStub mBindStub = new IMusicServiceStub(this);

    /**
     * 是否音乐正在播放
     */
    private boolean isMusicPlaying = false;

    /**
     * 工作线程和Handler
     */
    private MusicPlayerHandler mHandler;
    private HandlerThread mWorkThread;

    /**
     * 是否显示桌面歌词
     */
    private boolean showLyric;

    /**
     * 用于收集RxJava的订阅返回   onDestroy 的时候统一取消订阅
     */
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    /**
     * 通知栏的布局
     */
    private RemoteViews notRemoteView;

    /**
     * 主线程Handler
     */
    public Handler mMainHandler;

    /**
     * 封装后的播放器
     */
    public MusicPlayerEngine mPlayer = null;

    /**
     * 电源锁
     */
    public PowerManager.WakeLock mWakeLock = null;

    /**
     * 当前正在播放的歌曲
     */
    public SongBean mPlayingMusic = null;

    @Override
    public void onCreate() {
        super.onCreate();
        //初始化广播
        initReceiver();
        //初始化参数
        initConfig();
        //初始化电话监听服务
        initTelephony();
        //初始化通知
        initNotify();
        //初始化音乐播放服务
        initMediaPlayer();
    }

    /**
     * 初始化广播
     */
    private void initReceiver() {
        // 通过广播的方式控制播放器的Receiver
        mServiceReceiver = new ServiceReceiver();
        // 耳机插入拔出广播监听
        mHeadsetReceiver = new HeadsetReceiver();
        mHeadsetPlugInReceiver = new HeadsetPlugInReceiver();
        //
        IntentFilter intentFilter = new IntentFilter(Constants.ACTION_SERVICE);
        intentFilter.addAction(Constants.ACTION_NEXT);
        intentFilter.addAction(Constants.ACTION_PREV);
        intentFilter.addAction(Constants.SHUTDOWN);
        intentFilter.addAction(Constants.ACTION_PLAY_PAUSE);
        //注册广播
        registerReceiver(mServiceReceiver, intentFilter);
        registerReceiver(mHeadsetReceiver, intentFilter);
        registerReceiver(mHeadsetPlugInReceiver, intentFilter);
    }

    /**
     * 参数配置，AudioManager、锁屏
     */
    @SuppressLint("InvalidWakeLockTag")
    private void initConfig() {
        //初始化主线程Handler
        mMainHandler = new Handler(Looper.getMainLooper());
        PlayModeManager.INSTANCE.getPlayModeId();
        //初始化工作线程
        mWorkThread = new HandlerThread("MusicPlayerThread");
        mWorkThread.start();
        mHandler = new MusicPlayerHandler(this, mWorkThread.getLooper());
        //电源键
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PLAYER_WAKE_LOCK_TAG");
        }
        // 初始化歌词管理器
        mFloatLyricViewManager = new FloatLyricViewManager(this);
        //初始化和设置MediaSessionCompat
        mediaSessionManager = new MediaSessionManager(mBindStub, this, mMainHandler);
        audioAndFocusManager = new AudioAndFocusManager(this, mHandler);
    }

    /**
     * 初始化电话监听服务
     */
    private void initTelephony() {
        // 获取电话通讯服务
        TelephonyManager telephonyManager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        // 创建一个监听对象，监听电话状态改变事件
        if (telephonyManager != null) {
            telephonyManager.listen(new ServicePhoneStateListener(), PhoneStateListener.LISTEN_CALL_STATE);
        }
    }

    /**
     * 初始化音乐播放服务
     */
    private void initMediaPlayer() {
        mPlayer = new MusicPlayerEngine(this, mHandler);
        reloadPlayQueue();
    }

    /**
     * 初始化通知栏
     */
    private void initNotify() {
        // 获取发送通知的服务
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // 打开播放界面
        Intent nowPlayingIntent = new Intent();
        nowPlayingIntent.setClassName(this, Constants.PLAY_ACTIVITY_CLASS);
        // 如果当前的Activity已经显示在顶部  则不启动新的Activity
        nowPlayingIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // 通知被点击后发送intent  getActivity  表示Intent被Activity注册的广播接收器接收
        PendingIntent clickIntent = PendingIntent.getActivity(this, 0, nowPlayingIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        // 创建NotificationCompat.Builder
        mNotificationBuilder = new NotificationCompat.Builder(this, initNotifyChannel())
                .setSmallIcon(R.drawable.ic_icon)
                .setContentIntent(clickIntent)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP));
        // 小布局
        notRemoteView = new RemoteViews(getPackageName(), R.layout.player_notification);
        // 设置RemoteViews
        setupNotification(notRemoteView);
        mNotificationBuilder.setCustomContentView(notRemoteView);
        // 创建通知
        mNotification = mNotificationBuilder.build();
    }

    /**
     * 释放通知栏;
     */
    private void releaseServiceUiAndStop() {
        if (isPlaying() || mHandler.hasMessages(Constants.TRACK_PLAY_ENDED)) {
            return;
        }
        cancelNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager.release();
        }
    }

    /**
     * 重新加载当前播放进度
     */
    public void reloadPlayQueue() {
        // 加载播放历史
        Disposable disposable = PlayListHelper.INSTANCE.getSongListByPlayListId(PlayListBean.PLAYLIST_QUEUE_ID)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(songBeans -> {
                    mPlayQueue.clear();
                    mPlayQueue.addAll(songBeans);
                    // 获取歌曲的播放位置
                    mPlayingPos = SPUtils.getPlayPosition();
                    if (mPlayingPos >= 0 && mPlayingPos < mPlayQueue.size()) {
                        // 切换歌曲
                        mPlayingMusic = mPlayQueue.get(mPlayingPos);
                        updateNotification(false);
                        notifyChange(Constants.META_CHANGED);
                    }
                    // 歌单变化
                    notifyChange(Constants.PLAY_QUEUE_CHANGE);
                }, Throwable::printStackTrace);

        compositeDisposable.add(disposable);
    }

    /**
     * 启动Service服务，执行onStartCommand
     *
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final String action = intent.getAction();
            if (Constants.SHUTDOWN.equals(action)) {
                releaseServiceUiAndStop();
            } else {
                handleCommandIntent(intent);
            }
        }
        return START_NOT_STICKY;
    }

    /**
     * 绑定Service
     *
     * @param intent
     * @return
     */
    @Override
    public IBinder onBind(Intent intent) {
        return mBindStub;
    }

    /**
     * 下一首
     */
    public void next(Boolean isAuto) {
        // 获取下一首歌的播放位置
        mPlayingPos = getNextPosition(isAuto);
        // 停止当前正在播放的歌曲
        stop(false);
        // 切换歌曲
        playCurrentAndNext();
    }

    /**
     * 上一首
     */
    public void prev() {
        // 获取上一首歌位置
        mPlayingPos = getPreviousPosition();
        // 停止当前正在播放的歌曲
        stop(false);
        // 切换歌曲
        playCurrentAndNext();
    }

    /**
     * 播放当前歌曲
     * 调用这个方法之前需要修改 mPlayingPos  否则调用无效
     */
    private void playCurrentAndNext() {
        if (mPlayingPos >= mPlayQueue.size() || mPlayingPos < 0) {
            return;
        }
        // 从播放队列中获取需要播放的音乐
        mPlayingMusic = mPlayQueue.get(mPlayingPos);
        notifyChange(Constants.META_CHANGED);
        // 保存播放历史
        saveHistory();
        // 设置为正在播放
        isMusicPlaying = true;
        // 设置播放源
        mPlayer.setDataSource(mPlayingMusic.getUrl());
        // 更新媒体状态
        mediaSessionManager.updateMetaData(mPlayingMusic);
        // 请求音频焦点
        audioAndFocusManager.requestAudioFocus();
        // 更新通知栏
        updateNotification(false);

        // 发送广播  通知系统媒体
        final Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        sendBroadcast(intent);

        // 判断是否初始化
        mHandler.removeMessages(Constants.VOLUME_FADE_DOWN);
        //组件调到正常音量
        mHandler.sendEmptyMessage(Constants.VOLUME_FADE_UP);
        isMusicPlaying = true;
    }

    /**
     * 停止播放
     *
     * @param removeStatusIcon
     */
    public void stop(boolean removeStatusIcon) {
        if (mPlayer != null && mPlayer.isInitialized()) {
            // 停止播放引擎中正在播放的歌曲
            mPlayer.stop();
        }

        if (removeStatusIcon) {
            // 取消通知栏
            cancelNotification();
        }

        if (removeStatusIcon) {
            // 修改播放的状态
            isMusicPlaying = false;
        }
    }

    /**
     * 获取下一首位置
     *
     * @return
     */
    private int getNextPosition(Boolean isAuto) {
        int playModeId = PlayModeManager.INSTANCE.getPlayModeId();
        if (mPlayQueue == null || mPlayQueue.isEmpty()) {
            return -1;
        }
        if (mPlayQueue.size() == 1) {
            return 0;
        }
        if (playModeId == PlayModeManager.PLAY_MODE_REPEAT && isAuto) {
            if (mPlayingPos < 0) {
                return 0;
            } else {
                return mPlayingPos;
            }
        } else if (playModeId == PlayModeManager.PLAY_MODE_RANDOM) {
            return new Random().nextInt(mPlayQueue.size());
        } else {
            if (mPlayingPos == mPlayQueue.size() - 1) {
                return 0;
            } else if (mPlayingPos < mPlayQueue.size() - 1) {
                return mPlayingPos + 1;
            }
        }
        return mPlayingPos;
    }

    /**
     * 获取上一首位置
     *
     * @return
     */
    private int getPreviousPosition() {
        int playModeId = PlayModeManager.INSTANCE.getPlayModeId();
        if (mPlayQueue == null || mPlayQueue.isEmpty()) {
            return -1;
        }
        if (mPlayQueue.size() == 1) {
            return 0;
        }
        if (playModeId == PlayModeManager.PLAY_MODE_REPEAT) {
            if (mPlayingPos < 0) {
                return 0;
            }
        } else if (playModeId == PlayModeManager.PLAY_MODE_RANDOM) {
            mPlayingPos = new Random().nextInt(mPlayQueue.size());
            return new Random().nextInt(mPlayQueue.size());
        } else {
            if (mPlayingPos == 0) {
                return mPlayQueue.size() - 1;
            } else if (mPlayingPos > 0) {
                return mPlayingPos - 1;
            }
        }
        return mPlayingPos;
    }

    /**
     * 根据位置播放音乐
     *
     * @param position
     */
    public void playMusic(int position) {
        if (position >= mPlayQueue.size() || position == -1) {
            mPlayingPos = getNextPosition(true);
        } else {
            mPlayingPos = position;
        }
        if (mPlayingPos == -1) {
            return;
        }
        playCurrentAndNext();
    }

    /**
     * 音乐播放
     */
    public void play() {
        if (mPlayer.isInitialized()) {
            // 启动播放
            mPlayer.start();
            // 修改播放状态
            isMusicPlaying = true;
            // 全局发送播放状态改变的广播
            notifyChange(Constants.PLAY_STATE_CHANGED);
            // 请求音频焦点
            audioAndFocusManager.requestAudioFocus();
            // 调高音量
            mHandler.removeMessages(Constants.VOLUME_FADE_DOWN);
            mHandler.sendEmptyMessage(Constants.VOLUME_FADE_UP);
            // 更新通知栏
            updateNotification(true);
        } else {
            playCurrentAndNext();
        }
    }

    /**
     * 获取播放器的SessionId  用于标识播放出错的时候是哪个播放器播放出错
     *
     * @return
     */
    public int getAudioSessionId() {
        return mPlayer.getAudioSessionId();
    }

    /**
     * 指定一首歌曲进行播放
     *
     * @param music
     */
    public void play(SongBean music) {
        boolean hasSong = false;
        // 去播放队列中寻找是否已经存在当前正在播放的歌曲了
        int hasPosition = 0;
        for (; hasPosition < mPlayQueue.size(); hasPosition++) {
            if (music.equals(mPlayQueue.get(hasPosition))) {
                hasSong = true;
                break;
            }
        }
        if (hasSong) {
            // 播放队列中已经存在需要播放的歌曲了
            mPlayingPos = hasPosition;
        } else {
            if (mPlayingPos == -1 || mPlayQueue.size() == 0) {
                // 播放的是第一首歌曲
                mPlayQueue.add(music);
                mPlayingPos = 0;
            } else if (mPlayingPos < mPlayQueue.size()) {
                // 直接添加到 mPlayingPos 地方去
                mPlayQueue.add(mPlayingPos, music);
            }
        }
        // 执行播放操作
        playCurrentAndNext();
    }

    /**
     * 下一首播放
     *
     * @param music 设置的歌曲
     */
    public void nextPlay(SongBean music) {
        if (mPlayQueue.size() == 0) {
            play(music);
        } else if (mPlayingPos < mPlayQueue.size()) {
            mPlayQueue.add(mPlayingPos + 1, music);
        }
    }

    /**
     * 切换歌单播放
     * 1、歌单不一样切换
     */
    public void play(List<SongBean> musicList, int id, String playListId) {
        if (musicList.size() <= id) {
            return;
        }
        if (!mPlaylistId.equals(playListId) || mPlayQueue.size() == 0 || mPlayQueue.size() != musicList.size()) {
            setPlayQueue(musicList);
            mPlaylistId = playListId;
        }
        mPlayingPos = id;
        playCurrentAndNext();
    }

    /**
     * 播放暂停
     */
    public void playPause() {
        if (isPlaying()) {
            pause();
        } else {
            if (mPlayer.isInitialized()) {
                play();
            } else {
                isMusicPlaying = true;
                playCurrentAndNext();
            }
        }
    }

    /**
     * 暂停播放
     */
    public void pause() {
        // 将音量调低
        mHandler.removeMessages(Constants.VOLUME_FADE_UP);
        mHandler.sendEmptyMessage(Constants.VOLUME_FADE_DOWN);

        if (isPlaying()) {
            isMusicPlaying = false;
            // 通知播放状态
            notifyChange(Constants.PLAY_STATE_CHANGED);
            // 更新状态栏
            updateNotification(true);
            // 实时向外发送播放器状态
            Disposable subscribe = Observable.interval(200, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(aLong -> {
                        final Intent intent = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
                        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
                        //由系统接收,通知系统audio_session将关闭,不再使用音效
                        sendBroadcast(intent);
                        mPlayer.pause();
                    }, Throwable::printStackTrace);

            compositeDisposable.add(subscribe);
        }
    }

    /**
     * 跳到输入的进度
     */
    public void seekTo(int pos) {
        // mPlayingMusic!=null  已经指定了播放歌曲
        if (mPlayer != null && mPlayer.isInitialized() && mPlayingMusic != null) {
            mPlayer.seek(pos);
        }
    }

    /**
     * 保存播放队列
     *
     * @param full 是否存储歌单数据
     */
    private void savePlayQueue(boolean full) {
        if (full) {
            PlayListHelper.INSTANCE.addMusicList(PlayListBean.PLAYLIST_QUEUE_ID, mPlayQueue);
        }
        if (mPlayingMusic != null) {
            //保存歌曲id
            SPUtils.saveCurrentSongId(mPlayingMusic.getMid());
        }
        //保存播放的位置
        SPUtils.setPlayPosition(mPlayingPos);
        //保存歌曲的进度
        SPUtils.savePosition(getCurrentPosition());
        notifyChange(Constants.PLAY_QUEUE_CHANGE);
    }

    /**
     * 保存播放历史歌单
     */
    private void saveHistory() {
        savePlayQueue(false);
    }

    /**
     * 从播放队列中移出一首歌
     *
     * @param position
     */
    public void removeFromQueue(int position) {
        try {
            if (position == mPlayingPos) {
                mPlayQueue.remove(position);
                if (mPlayQueue.size() == 0) {
                    clearQueue();
                } else {
                    playMusic(position);
                }
            } else if (position > mPlayingPos) {
                mPlayQueue.remove(position);
            } else {
                mPlayQueue.remove(position);
                mPlayingPos = mPlayingPos - 1;
            }
            notifyChange(Constants.PLAY_QUEUE_CLEAR);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 清空所有的播放队列并停止播放
     */
    public void clearQueue() {
        mPlayingMusic = null;
        isMusicPlaying = false;
        mPlayingPos = -1;
        mPlayQueue.clear();
        savePlayQueue(true);
        stop(true);
        notifyChange(Constants.META_CHANGED);
        notifyChange(Constants.PLAY_STATE_CHANGED);
        notifyChange(Constants.PLAY_QUEUE_CLEAR);
    }

    /**
     * 全局发送广播
     *
     * @param what 发送更新广播
     */
    public void notifyChange(final String what) {
        switch (what) {
            case Constants.META_CHANGED:
                // 歌曲切换 通知歌词更新
                mFloatLyricViewManager.loadLyric(mPlayingMusic);
                // 更新Widget
                updateWidget(Constants.META_CHANGED);
                break;
            case Constants.PLAY_STATE_CHANGED:
                updateWidget(Constants.PLAY_STATE_CHANGED);
                mediaSessionManager.updatePlaybackState();
                break;
            case Constants.PLAY_QUEUE_CLEAR:
            case Constants.PLAY_QUEUE_CHANGE:
                // TODO 播放队列清除  或者播放队列改变
                break;
            case Constants.DURATION_CHANGED:
                updateWidget(Constants.DURATION_CHANGED);
                break;
            default:
                break;
        }
    }

    /**
     * 更新桌面小控件
     */
    private void updateWidget(String action) {
        Intent intent = new Intent(action);
        // 发送播放暂停状态
        intent.putExtra(Constants.PLAY_STATUS, isPlaying());
        // 是否发送歌曲信息
        if (action.equals(Constants.META_CHANGED)) {
            intent.putExtra(Constants.SONG, mPlayingMusic);
        }
        sendBroadcast(intent);
    }

    /**
     * 创建Notification ChannelID
     *
     * @return 频道id
     */
    private String initNotifyChannel() {
        // 通知渠道的id
        String id = "delicate_music_01";
        // 用户可以看到的通知渠道的名字.
        CharSequence name = "DelicateMusic";
        // 用户可以看到的通知渠道的描述
        String description = "DelicateMusicController";
        // Android 8.0需要创建这个通道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel mChannel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW);
            mChannel.setDescription(description);
            mChannel.enableLights(false);
            mChannel.enableVibration(false);
            //最后在 mNotificationManager 中创建该通知渠道
            mNotificationManager.createNotificationChannel(mChannel);
        }
        return id;
    }

    /**
     * 这个通知栏布局中按钮的点击发送的Intent类型
     *
     * @param remoteViews
     */
    private void setupNotification(RemoteViews remoteViews) {
        // 播放暂停
        remoteViews.setOnClickPendingIntent(R.id.notificationPlayPause, retrievePlaybackAction(Constants.ACTION_PLAY_PAUSE));
        // 关闭
        remoteViews.setOnClickPendingIntent(R.id.notificationStop, retrievePlaybackAction(Constants.ACTION_CLOSE));
        // 上一曲
        remoteViews.setOnClickPendingIntent(R.id.notificationFRewind, retrievePlaybackAction(Constants.ACTION_PREV));
        // 下一曲
        remoteViews.setOnClickPendingIntent(R.id.notificationFForward, retrievePlaybackAction(Constants.ACTION_NEXT));
        // 词
        remoteViews.setOnClickPendingIntent(R.id.notificationLyric, retrievePlaybackAction(Constants.ACTION_LYRIC));
        // 播放模式
        remoteViews.setOnClickPendingIntent(R.id.notificationRepeat, retrievePlaybackAction(Constants.ACTION_REPEAT));
    }

    private PendingIntent retrievePlaybackAction(final String action) {
        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(this, MusicPlayerService.class));
        return PendingIntent.getService(this, 0, intent, 0);
    }

    private Disposable lyricDisposable;

    public void showDesktopLyric(boolean show) {
        if (show) {
            // 开启定时器，每隔0.2秒刷新一次
            lyricDisposable = Observable.interval(200, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(aLong -> mFloatLyricViewManager.updateLyric(getCurrentPosition(), getDuration()), Throwable::printStackTrace);
        } else {
            if (lyricDisposable != null && !lyricDisposable.isDisposed()) {
                lyricDisposable.dispose();
            }
            // 移除桌面歌词
            mFloatLyricViewManager.removeFloatLyricView(this);
        }
    }

    /**
     * 更新通知栏
     */
    private void updateNotification(boolean changePlayStatus) {
        Disposable disposable = DownLoadManager.INSTANCE.downImage(mPlayingMusic.getImage()).subscribe(bitmap -> {
            if (!changePlayStatus) {
                // 更新UI
                final String songName = getSongName();
                final String singerName = getSingerName();
                int playButtonResId = isMusicPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
                notRemoteView.setTextViewText(R.id.notificationSongName, songName);
                notRemoteView.setTextViewText(R.id.notificationArtist, singerName);
                notRemoteView.setImageViewResource(R.id.notificationPlayPause, playButtonResId);
                notRemoteView.setImageViewBitmap(R.id.notificationCover, bitmap);
            }
            // 重新设置自定义View
            mNotificationBuilder.setCustomContentView(notRemoteView);
            mNotification = mNotificationBuilder.build();
            // 更新歌词
            mFloatLyricViewManager.updatePlayStatus(isMusicPlaying);
            // 显示到通知栏
            startForeground(Constants.NOTIFICATION_ID, mNotification);
            // 必须要主动发送一次到通知栏,否则会有兼容问题
            mNotificationManager.notify(Constants.NOTIFICATION_ID, mNotification);
        }, Throwable::printStackTrace);
        compositeDisposable.add(disposable);
    }

    /**
     * 取消通知
     */
    private void cancelNotification() {
        stopForeground(true);
        mNotificationManager.cancel(Constants.NOTIFICATION_ID);
    }

    /**
     * Intent处理
     *
     * @param intent
     */
    private void handleCommandIntent(Intent intent) {
        // 获取action
        final String action = intent.getAction();
        // 获取发送的命令
        final String command = Constants.SERVICE_CMD.equals(action) ? intent.getStringExtra(Constants.CMD_NAME) : null;
        if (Constants.CMD_NEXT.equals(command) || Constants.ACTION_NEXT.equals(action)) {
            // 下一曲
            next(false);
        } else if (Constants.CMD_PREVIOUS.equals(command) || Constants.ACTION_PREV.equals(action)) {
            // 上一曲
            prev();
        } else if (Constants.CMD_TOGGLE_PAUSE.equals(command) || Constants.PLAY_STATE_CHANGED.equals(action) || Constants.ACTION_PLAY_PAUSE.equals(action)) {
            // toggle播放状态
            if (isPlaying()) {
                pause();
            } else {
                play();
            }
        } else if (Constants.CMD_PAUSE.equals(command)) {
            // 点击暂停按钮
            pause();
        } else if (Constants.CMD_PLAY.equals(command)) {
            // 点击播放按钮
            play();
        } else if (Constants.CMD_STOP.equals(command)) {
            // 点击停止按钮
            pause();
            seekTo(0);
            // 释放服务 并 停止
            releaseServiceUiAndStop();
        } else if (Constants.ACTION_LYRIC.equals(action)) {
            // 点击了歌词按钮
            startFloatLyric();
        } else if (Constants.ACTION_CLOSE.equals(action)) {
            // 点击了通知栏的关闭按钮
            stop(true);
            releaseServiceUiAndStop();
            stopSelf();
        } else if (Constants.ACTION_REPEAT.equals(action)) {
            // 点击了通知栏的播放模式切换按钮
            PlayModeManager.INSTANCE.updatePlayMode();
            // 更新通知栏的显示图标

        }
    }

    /**
     * 开启歌词
     */
    private void startFloatLyric() {
        if (SystemUtils.isOpenFloatWindow()) {
            showLyric = !showLyric;
            // 显示桌面歌词
            showDesktopLyric(showLyric);
        } else {
            // 跳转到悬浮窗权限获取界面
            SystemUtils.applySystemWindow();
        }
    }

    /**
     * 接收由通知栏  桌面小部件发送命令  用于控制播放器
     */
    private class ServiceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleCommandIntent(intent);
        }
    }

    /**
     * 电话监听
     */
    private class ServicePhoneStateListener extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            switch (state) {
                //通话状态
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    //通话状态
                case TelephonyManager.CALL_STATE_RINGING:
                    pause();
                    break;
                case TelephonyManager.CALL_STATE_IDLE:
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 耳机插入广播接收器
     */
    public class HeadsetPlugInReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.hasExtra("state")) {
                //通过判断 "state" 来知道状态
                final boolean isPlugIn = intent.getIntExtra("state", 0) == 1;
                Logger.e(TAG, "耳机插入状态 ：" + isPlugIn);
            }
        }
    }

    /**
     * 耳机拔出广播接收器
     */
    private class HeadsetReceiver extends BroadcastReceiver {
        private final BluetoothAdapter bluetoothAdapter;

        public HeadsetReceiver() {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                //当前是正在运行的时候才能通过媒体按键来操作音频
                String action = intent.getAction();
                action = action != null ? action : "";
                switch (action) {
                    // 蓝牙耳机的连接状态发生改变
                    case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                        if (bluetoothAdapter != null && BluetoothProfile.STATE_DISCONNECTED == bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) && isPlaying()) {
                            //蓝牙耳机断开连接 同时当前音乐正在播放 则将其暂停
                            pause();
                        }
                        break;
                    case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                        if (isPlaying()) {
                            //有线耳机断开连接 同时当前音乐正在播放 则将其暂停
                            pause();
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void execDestroy() {
        // 关闭audio媒体中心
        final Intent audioEffectsIntent = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        audioEffectsIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
        audioEffectsIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        sendBroadcast(audioEffectsIntent);
        // 保存播放队列
        savePlayQueue(false);
        //释放mPlayer
        if (mPlayer != null) {
            isMusicPlaying = false;
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
        }
        // 释放Handler资源
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
        // 释放工作线程资源
        if (mWorkThread != null && mWorkThread.isAlive()) {
            mWorkThread.quitSafely();
            mWorkThread.interrupt();
            mWorkThread = null;
        }
        // 取消桌面歌词显示
        showDesktopLyric(false);
        // 释放音频焦点
        audioAndFocusManager.abandonAudioFocus();
        // 取消通知栏
        cancelNotification();
        // 注销广播
        unregisterReceiver(mServiceReceiver);
        unregisterReceiver(mHeadsetReceiver);
        unregisterReceiver(mHeadsetPlugInReceiver);
        // 释放电源锁
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    @Override
    public void onDestroy() {
        execDestroy();
        super.onDestroy();
    }

    /**
     * 是否正在播放音乐
     *
     * @return 是否正在播放音乐
     */
    public boolean isPlaying() {
        return isMusicPlaying;
    }

    /**
     * 获取正在播放的音乐的进度
     */
    public int getCurrentPosition() {
        if (mPlayer != null && mPlayer.isInitialized()) {
            return mPlayer.position();
        } else {
            return 0;
        }
    }

    /**
     * 获取总时长
     */
    public int getDuration() {
        if (mPlayer != null && mPlayer.isInitialized() && mPlayer.isPrepared()) {
            return mPlayer.duration();
        } else {
            return 0;
        }
    }

    /**
     * 获取当前播放器的id
     *
     * @return
     */
    public int getAudioId() {
        if (mPlayingMusic != null) {
            return mPlayer.getAudioSessionId();
        } else {
            return -1;
        }
    }

    /**
     * 播放器播放状态是否准备完毕
     *
     * @return
     */
    public boolean isPrepared() {
        if (mPlayer != null) {
            return mPlayer.isPrepared();
        } else {
            return false;
        }
    }

    /**
     * 获取标题
     *
     * @return
     */
    public String getSongName() {
        if (mPlayingMusic != null) {
            return mPlayingMusic.getSongname();
        } else {
            return "";
        }
    }

    /**
     * 获取歌手专辑
     *
     * @return
     */
    public String getSingerName() {
        if (mPlayingMusic != null) {
            return mPlayingMusic.getSingername();
        } else {
            return "";
        }
    }

    /**
     * 获取当前正在播放的音乐
     *
     * @return
     */
    public SongBean getPlayingMusic() {
        if (mPlayingMusic != null) {
            return mPlayingMusic;
        } else {
            return null;
        }
    }

    /**
     * 获取播放队列
     *
     * @return 获取播放队列
     */
    public List<SongBean> getPlayQueue() {
        return mPlayQueue;
    }

    /**
     * 获取当前音乐在播放队列中的位置
     *
     * @return 当前音乐在播放队列中的位置
     */
    public int getPlayPosition() {
        if (mPlayingPos >= 0) {
            return mPlayingPos;
        } else {
            return 0;
        }
    }

    /**
     * 更换播放队列
     *
     * @param playQueue 播放队列
     */
    public void setPlayQueue(List<SongBean> playQueue) {
        mPlayQueue.clear();
        mPlayQueue.addAll(playQueue);
        // 保存一次播放队列
        savePlayQueue(true);
    }
}
