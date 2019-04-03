package com.kaibo.music.player.player.receiver

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Message
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.view.KeyEvent
import com.kaibo.music.player.player.service.MusicPlayerService

class MediaButtonIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val intentAction = intent.action
        if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intentAction) {
            //当耳机拔出时暂停播放
//            if (isMusicServiceRunning(context)) {
//                val i = Intent(context, MusicPlayerService::class.java)
//                i.action = Constants.SERVICE_CMD
//                i.putExtra(Constants.CMD_NAME, Constants.CMD_PAUSE)
//                context.startService(i)
//            }
        } else if (Intent.ACTION_MEDIA_BUTTON == intentAction) {
            //耳机按钮事件
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            val keycode = event.keyCode
            val action = event.action
            val eventtime = event.eventTime
            var command: String? = null
//            when (keycode) {
//                KeyEvent.KEYCODE_MEDIA_STOP -> command = Constants.CMD_STOP
//                KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> command = Constants.CMD_TOGGLE_PAUSE
//                KeyEvent.KEYCODE_MEDIA_NEXT -> command = Constants.CMD_NEXT
//                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> command = Constants.CMD_PREVIOUS
//                KeyEvent.KEYCODE_MEDIA_PAUSE -> command = Constants.CMD_PAUSE
//                KeyEvent.KEYCODE_MEDIA_PLAY -> command = Constants.CMD_PLAY
//                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> command = Constants.CMD_FORWARD
//                KeyEvent.KEYCODE_MEDIA_REWIND -> command = Constants.CMD_REWIND
//                else -> {
//                }
//            }
            if (command != null) {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (mDown) {
//                        if (Constants.CMD_TOGGLE_PAUSE.equals(command) || Constants.CMD_PLAY.equals(command)) {
//                            if (mLastClickTime != 0L && eventtime - mLastClickTime > LONG_PRESS_DELAY) {
//                                acquireWakeLockAndSendMessage(context,
//                                        mHandler.obtainMessage(MSG_LONGPRESS_TIMEOUT, context), 0)
//                            }
//                        }
                    } else if (event.repeatCount == 0) {
                        if (keycode == KeyEvent.KEYCODE_HEADSETHOOK) {
                            if (eventtime - mLastClickTime >= DOUBLE_CLICK) {
                                mClickCounter = 0
                            }
                            mClickCounter++
                            mHandler.removeMessages(MSG_HEADSET_DOUBLE_CLICK_TIMEOUT)
                            val msg = mHandler.obtainMessage(MSG_HEADSET_DOUBLE_CLICK_TIMEOUT, mClickCounter, 0, context)
                            val delay = (if (mClickCounter < 3) DOUBLE_CLICK else 0).toLong()
                            if (mClickCounter >= 3) {
                                mClickCounter = 0
                            }
                            mLastClickTime = eventtime
                            acquireWakeLockAndSendMessage(context, msg, delay)
                        } else {
                            startService(context, command)
                        }
                        mLaunched = false
                        mDown = true
                    }
                } else {
                    mHandler.removeMessages(MSG_LONGPRESS_TIMEOUT)
                    mDown = false
                }
                if (isOrderedBroadcast) {
                    abortBroadcast()
                }
                releaseWakeLockIfHandlerIdle()
            }
        }
    }

    private fun isMusicServiceRunning(context: Context): Boolean {
        var isServiceRuning = false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val maxServciesNum = 100
        val list = am.getRunningServices(maxServciesNum)
        for (info in list) {
            if (MusicPlayerService::class.java.name == info.service.className) {
                isServiceRuning = true
                break
            }
        }
        return isServiceRuning
    }

    companion object {
        private val MSG_LONGPRESS_TIMEOUT = 1
        private val MSG_HEADSET_DOUBLE_CLICK_TIMEOUT = 2

        private val LONG_PRESS_DELAY = 1000
        private val DOUBLE_CLICK = 800

        private var mWakeLock: WakeLock? = null
        private var mClickCounter = 0
        private var mLastClickTime: Long = 0
        private var mDown = false
        private var mLaunched = false

        @SuppressLint("HandlerLeak")
        private val mHandler = object : Handler() {
            /**
             * {@inheritDoc}
             */
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_LONGPRESS_TIMEOUT -> if (!mLaunched) {
                        val context = msg.obj as Context
                        val i = Intent()
                        i.putExtra("autoshuffle", "true")
                        i.setClassName(context, "com.kaibo.music.activity.MainActivity")
                        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        context.startActivity(i)
                        mLaunched = true
                    }
                    MSG_HEADSET_DOUBLE_CLICK_TIMEOUT -> {
                        //双击时间阈值内
                        val clickCount = msg.arg1
//                        val command: String?
//                        when (clickCount) {
//                            1 -> command = Constants.CMD_TOGGLE_PAUSE
//                            2 -> command = Constants.CMD_NEXT
//                            3 -> command = SyncStateContract.Constants.CMD_PREVIOUS
//                            else -> command = null
//                        }
//
//                        if (command != null) {
//                            val context1 = msg.obj as Context
//                            startService(context1, command)
//                        }
                    }
                    else -> {
                    }
                }
            }
        }

        /**
         * 启动musicservice,并拥有wake_lock权限
         *
         * @param context
         * @param command
         */
        private fun startService(context: Context, command: String?) {
//            val i = Intent(context, MusicPlayerService::class.java)
//            i.action = Constants.SERVICE_CMD
//            i.putExtra(Constants.CMD_NAME, command)
//            i.putExtra(Constants.FROM_MEDIA_BUTTON, true)
//            context.startService(i)
        }

        @SuppressLint("InvalidWakeLockTag")
        private fun acquireWakeLockAndSendMessage(context: Context, msg: Message, delay: Long) {
            if (mWakeLock == null) {
                val appContext = context.applicationContext
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Listener headset button")
                //设置无论请求多少次vakelock,都只需一次释放
                mWakeLock!!.setReferenceCounted(false)
            }
            // Make sure we don't indefinitely hold the wake lock under any circumstances
            //防止无期限hold住wakelock
            mWakeLock!!.acquire(10000)
            mHandler.sendMessageDelayed(msg, delay)
        }

        /**
         * 如果handler的消息队列中没有待处理消息,就释放receiver hold住的wakelog
         */
        private fun releaseWakeLockIfHandlerIdle() {
            if (mHandler.hasMessages(MSG_LONGPRESS_TIMEOUT) || mHandler.hasMessages(MSG_HEADSET_DOUBLE_CLICK_TIMEOUT)) {
                return
            }
            if (mWakeLock != null) {
                mWakeLock!!.release()
                mWakeLock = null
            }
        }
    }
}
