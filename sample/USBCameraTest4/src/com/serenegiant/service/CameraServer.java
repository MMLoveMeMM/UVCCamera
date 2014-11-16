package com.serenegiant.service;
/*
 * UVCCamera
 * library and sample to access to UVC web camera on non-rooted Android device
 * 
 * Copyright (c) 2014 saki t_saki@serenegiant.com
 * 
 * File name: CameraServer.java
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * 
 * All files in the folder are under this Apache License, Version 2.0.
 * Files in the jni/libjpeg, jni/libusb and jin/libuvc folder may have a different license, see the respective files.
*/

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import com.serenegiant.encoder.MediaAudioEncoder;
import com.serenegiant.encoder.MediaEncoder;
import com.serenegiant.encoder.MediaMuxerWrapper;
import com.serenegiant.encoder.MediaSurfaceEncoder;
import com.serenegiant.glutils.RendererHolder;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usbcameratest4.R;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

public class CameraServer extends Handler {
	private static final boolean DEBUG = true;
	private static final String TAG = "CameraServer";
	
    private static class CallbackCookie {
		boolean isConnected;
	}

    private final RemoteCallbackList<IUVCServiceCallback> mCallbacks
		= new RemoteCallbackList<IUVCServiceCallback>();
    private int mRegisteredCallbackCount;

	private RendererHolder mRendererHolder;
	private final WeakReference<CameraThread> mWeakThread;

	public static CameraServer createServer(Context context, UsbControlBlock ctrlBlock, int vid, int pid) {
		if (DEBUG) Log.d(TAG, "createServer:");
		final CameraThread thread = new CameraThread(context, ctrlBlock);
		thread.start();
		return thread.getHandler();
	}

	private CameraServer(CameraThread thread) {
		if (DEBUG) Log.d(TAG, "Constructor:");
		mWeakThread = new WeakReference<CameraThread>(thread);
		mRegisteredCallbackCount = 0;
		mRendererHolder = new RendererHolder(null);
	}

	@Override
	protected void finalize() throws Throwable {
		if (DEBUG) Log.i(TAG, "finalize:");
		release();
		super.finalize();
	}

	public void registerCallback(IUVCServiceCallback callback) {
		if (DEBUG) Log.d(TAG, "registerCallback:");
		mCallbacks.register(callback, new CallbackCookie());
		mRegisteredCallbackCount++;
	}

	public boolean unregisterCallback(IUVCServiceCallback callback) {
		if (DEBUG) Log.d(TAG, "unregisterCallback:");
		mCallbacks.unregister(callback);
		mRegisteredCallbackCount--;
		if (mRegisteredCallbackCount < 0) mRegisteredCallbackCount = 0;
		return mRegisteredCallbackCount == 0;
	}

	public void release() {
		if (DEBUG) Log.d(TAG, "release:");
		disconnect();
		mCallbacks.kill();
		if (mRendererHolder != null) {
			mRendererHolder.release();
			mRendererHolder = null;
		}
	}

//********************************************************************************
//********************************************************************************
	public void connect() {
		if (DEBUG) Log.d(TAG, "connect:");
		final CameraThread thread = mWeakThread.get();
		if (!thread.isCameraOpened()) {
			sendMessage(obtainMessage(MSG_OPEN));
			sendMessage(obtainMessage(MSG_PREVIEW_START, mRendererHolder.getSurface()));
		} else {
			if (DEBUG) Log.d(TAG, "already connected, just call callback");
			processOnCameraStart();
		}
	}

	public void connectSlave() {
		if (DEBUG) Log.d(TAG, "connectSlave:");
		final CameraThread thread = mWeakThread.get();
		if (thread.isCameraOpened()) {
			processOnCameraStart();
		}
	}

	public void disconnect() {
		if (DEBUG) Log.d(TAG, "disconnect:");
		stopRecording();
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		synchronized (thread.mSync) {
			sendEmptyMessage(MSG_PREVIEW_STOP);
			// wait for actually preview stopped to avoid releasing Surface/SurfaceTexture
			// while preview is still running.
			// therefore this method will take a time to execute
			try {
				thread.mSync.wait();
			} catch (InterruptedException e) {
			}
		}
		sendEmptyMessage(MSG_CLOSE);
	}

	public boolean isConnected() {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isCameraOpened();
	}

	public boolean isRecording() {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isRecording();
	}

	public void addSurface(int id, Surface surface, boolean isRecordable, IUVCServiceOnFrameAvailable onFrameAvailableListener) {
		if (DEBUG) Log.d(TAG, "addSurface:id=" + id +",surface=" + surface);
		mRendererHolder.addSurface(id, surface, isRecordable, onFrameAvailableListener);
	}

	public void removeSurface(int id) {
		if (DEBUG) Log.d(TAG, "removeSurface:id=" + id);
		mRendererHolder.removeSurface(id);
	}


//********************************************************************************
	private void processOnCameraStart() {
		if (DEBUG) Log.d(TAG, "processOnCameraStart:");
		final int n = mCallbacks.beginBroadcast();
		for (int i = 0; i < n; i++) {
			if (!((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected)
			try {
				mCallbacks.getBroadcastItem(i).onConnected();
				((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected = true;
			} catch (Exception e) {
				Log.e(TAG, "failed to call IOverlayCallback#onFrameAvailable");
			}
		}
		mCallbacks.finishBroadcast();
	}

	private void processOnCameraStop() {
		if (DEBUG) Log.d(TAG, "processOnCameraStop:");
		final int n = mCallbacks.beginBroadcast();
		for (int i = 0; i < n; i++) {
			if (((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected)
			try {
				mCallbacks.getBroadcastItem(i).onDisConnected();
				((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected = false;
			} catch (Exception e) {
				Log.e(TAG, "failed to call IOverlayCallback#onDisConnected");
			}
		}
		mCallbacks.finishBroadcast();
	}

//**********************************************************************
	private static final int MSG_OPEN = 0;
	private static final int MSG_CLOSE = 1;
	private static final int MSG_PREVIEW_START = 2;
	private static final int MSG_PREVIEW_STOP = 3;
	private static final int MSG_CAPTURE_STILL = 4;
	private static final int MSG_CAPTURE_START = 5;
	private static final int MSG_CAPTURE_STOP = 6;
	private static final int MSG_MEDIA_UPDATE = 7;
	private static final int MSG_RELEASE = 9;

	public void captureStill() {
		sendEmptyMessage(MSG_CAPTURE_STILL);
	}

	public void startRecording() {
		if (!isRecording())
			sendEmptyMessage(MSG_CAPTURE_START);
	}

	public void stopRecording() {
		if (isRecording())
			sendEmptyMessage(MSG_CAPTURE_STOP);
	}

	@Override
	public void handleMessage(Message msg) {
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		switch (msg.what) {
		case MSG_OPEN:
			thread.handleOpen();
			break;
		case MSG_CLOSE:
			thread.handleClose();
			break;
		case MSG_PREVIEW_START:
			thread.handleStartPreview((Surface)msg.obj);
			break;
		case MSG_PREVIEW_STOP:
			thread.handleStopPreview();
			break;
		case MSG_CAPTURE_STILL:
			thread.handleCaptureStill();
			break;
		case MSG_CAPTURE_START:
			thread.handleStartRecording();
			break;
		case MSG_CAPTURE_STOP:
			thread.handleStopRecording();
			break;
		case MSG_MEDIA_UPDATE:
			thread.handleUpdateMedia((String)msg.obj);
			break;
		case MSG_RELEASE:
			thread.handleRelease();
			break;
		default:
			throw new RuntimeException("unsupported message:what=" + msg.what);
		}
	}

	private static final class CameraThread extends Thread {
		private static final String TAG_THREAD = "CameraServiceThread";
		private final Object mSync = new Object();
		private boolean mIsRecording;
	    private final WeakReference<Context> mWeakContext;
		private int mEncoderSurfaceId;
		/**
		 * shutter sound
		 */
		private SoundPool mSoundPool;
		private int mSoundId;
		private CameraServer mHandler;
		private UsbControlBlock mCtrlBlock;
		/**
		 * for accessing UVC camera 
		 */
		private UVCCamera mUVCCamera;
		/**
		 * muxer for audio/video recording
		 */
		private MediaMuxerWrapper mMuxer;
		private MediaSurfaceEncoder mVideoEncoder;

		private CameraThread(Context context, UsbControlBlock ctrlBlock) {
			super("CameraThread");
			if (DEBUG) Log.d(TAG_THREAD, "Constructor:");
			mWeakContext = new WeakReference<Context>(context);
			mCtrlBlock = ctrlBlock;
			loadSutterSound(context);
		}

		@Override
		protected void finalize() throws Throwable {
			Log.i(TAG_THREAD, "CameraThread#finalize");
			super.finalize();
		}

		public CameraServer getHandler() {
			if (DEBUG) Log.d(TAG_THREAD, "getHandler:");
			synchronized (mSync) {
				if (mHandler == null)
				try {
					mSync.wait();
				} catch (InterruptedException e) {
				}
			}
			return mHandler;
		}

		public boolean isCameraOpened() {
			synchronized (mSync) {
				return mUVCCamera != null;
			}
		}

		public boolean isRecording() {
			synchronized (mSync) {
				return (mUVCCamera != null) && (mMuxer != null);
			}
		}

		public void handleOpen() {
			if (DEBUG) Log.d(TAG_THREAD, "handleOpen:");
			handleClose();
			final UVCCamera camera = new UVCCamera();
			camera.open(mCtrlBlock);
			synchronized (mSync) {
				mUVCCamera = camera;
			}
			mHandler.processOnCameraStart();
		}

		public void handleClose() {
			if (DEBUG) Log.d(TAG_THREAD, "handleClose:");
			handleStopRecording();
			final UVCCamera camera;
			synchronized (mSync) {
				camera = mUVCCamera;
				mUVCCamera = null;
			}
			if (camera != null) {
				camera.stopPreview();
				camera.destroy();
				mHandler.processOnCameraStop();
			}
		}

		public void handleStartPreview(Surface surface) {
			if (DEBUG) Log.d(TAG_THREAD, "handleStartPreview:");
			synchronized (mSync) {
				if (mUVCCamera == null) return;
				mUVCCamera.setPreviewDisplay(surface);
				mUVCCamera.startPreview();
			}
		}

		public void handleStopPreview() {
			if (DEBUG) Log.d(TAG_THREAD, "handleStopPreview:");
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
				}
				mSync.notifyAll();
			}
		}

		public void handleCaptureStill() {
			if (DEBUG) Log.d(TAG_THREAD, "handleCaptureStill:");
			mSoundPool.play(mSoundId, 0.2f, 0.2f, 0, 0, 1.0f);	// play shutter sound
			// FIXME un-implemented yet
		}

		public void handleStartRecording() {
			if (DEBUG) Log.d(TAG_THREAD, "handleStartRecording:");
			try {
				synchronized (mSync) {
					if ((mUVCCamera == null) || (mMuxer != null)) return;
					mMuxer = new MediaMuxerWrapper(".mp4");	// if you record audio only, ".m4a" is also OK.
				}
				new MediaSurfaceEncoder(mMuxer, mMediaEncoderListener);
				if (true) {
					// for audio capturing
					new MediaAudioEncoder(mMuxer, mMediaEncoderListener);
				}
				mMuxer.prepare();
				mMuxer.startRecording();
			} catch (IOException e) {
				Log.e(TAG, "startCapture:", e);
			}
		}

		public void handleStopRecording() {
			if (DEBUG) Log.d(TAG_THREAD, "handleStopRecording:mMuxer=" + mMuxer);
			final MediaMuxerWrapper muxer;
			synchronized (mSync) {
				muxer = mMuxer;
				mMuxer = null;
			}
			if (muxer != null) {
				muxer.stopRecording();
				// you should not wait here
			}
		}

		public void handleUpdateMedia(final String path) {
			if (DEBUG) Log.d(TAG_THREAD, "handleUpdateMedia:path=" + path);
			final Context context = mWeakContext.get();
			if (context != null) {
				try {
					if (DEBUG) Log.i(TAG, "MediaScannerConnection#scanFile");
					MediaScannerConnection.scanFile(context, new String[]{ path }, null, null);
				} catch (Exception e) {
					Log.e(TAG, "handleUpdateMedia:", e);
				}
			} else {
				Log.w(TAG, "MainActivity already destroyed");
				// give up to add this movice to MediaStore now.
				// Seeing this movie on Gallery app etc. will take a lot of time. 
				handleRelease();
			}
		}

		public void handleRelease() {
			if (DEBUG) Log.d(TAG_THREAD, "handleRelease:");
			handleClose();
			if (mCtrlBlock != null) {
				mCtrlBlock.close();
				mCtrlBlock = null;
			}
			if (!mIsRecording)
				Looper.myLooper().quit();
		}

		private final IUVCServiceOnFrameAvailable mOnFrameAvailable = new IUVCServiceOnFrameAvailable() {
			@Override
			public IBinder asBinder() {
				if (DEBUG) Log.d(TAG_THREAD, "asBinder:");
				return null;
			}
			@Override
			public void onFrameAvailable() throws RemoteException {
//				if (DEBUG) Log.d(TAG_THREAD, "onFrameAvailable:");
				if (mVideoEncoder != null)
					mVideoEncoder.frameAvailableSoon();
			}
		};

		private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
			@Override
			public void onPrepared(MediaEncoder encoder) {
				if (DEBUG) Log.d(TAG, "onPrepared:encoder=" + encoder);
				mIsRecording = true;
				if (encoder instanceof MediaSurfaceEncoder)
				try {
					mVideoEncoder = (MediaSurfaceEncoder)encoder;
					final Surface encoderSurface = mVideoEncoder.getInputSurface();
					mEncoderSurfaceId = encoderSurface.hashCode();
					mHandler.mRendererHolder.addSurface(mEncoderSurfaceId, encoderSurface, true, mOnFrameAvailable);
				} catch (Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
			}

			@Override
			public void onStopped(MediaEncoder encoder) {
				if (DEBUG) Log.v(TAG_THREAD, "onStopped:encoder=" + encoder);
				if ((encoder instanceof MediaSurfaceEncoder))
				try {
					mIsRecording = false;
					if (mEncoderSurfaceId > 0)
						mHandler.mRendererHolder.removeSurface(mEncoderSurfaceId);
					mEncoderSurfaceId = -1;
					mUVCCamera.stopCapture();
					mVideoEncoder = null;
					final String path = encoder.getOutputPath();
					if (!TextUtils.isEmpty(path)) {
						mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_MEDIA_UPDATE, path), 1000);
					}
				} catch (Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
			}
		};

		/**
		 * prepare and load shutter sound for still image capturing
		 */
		private void loadSutterSound(Context context) {
			if (DEBUG) Log.d(TAG_THREAD, "loadSutterSound:");
	    	// get system stream type using refrection
	        int streamType;
	        try {
	            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
	            final Field sseField = audioSystemClass.getDeclaredField("STREAM_SYSTEM_ENFORCED");
	            streamType = sseField.getInt(null);
	        } catch (Exception e) {
	        	streamType = AudioManager.STREAM_SYSTEM;	// set appropriate according to your app policy
	        }
	        if (mSoundPool != null) {
	        	try {
	        		mSoundPool.release();
	        	} catch (Exception e) {
	        	}
	        	mSoundPool = null;
	        }
	        // load sutter sound from resource
		    mSoundPool = new SoundPool(2, streamType, 0);
		    mSoundId = mSoundPool.load(context, R.raw.camera_click, 1);
		}

		@Override
		public void run() {
			if (DEBUG) Log.d(TAG_THREAD, "run:");
			Looper.prepare();
			synchronized (mSync) {
				mHandler = new CameraServer(this);
				mSync.notifyAll();
			}
			Looper.loop();
			synchronized (mSync) {
				mHandler = null;
				mSoundPool.release();
				mSoundPool = null;
				mSync.notifyAll();
			}
			if (DEBUG) Log.d(TAG_THREAD, "run:finished");
		}
	}

}
