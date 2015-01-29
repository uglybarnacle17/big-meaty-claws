/**
 * 
 */
package org.opensharingtoolkit.daoplayer.audio;

import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Vector;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.Obuffer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.AsyncTask;
import android.util.Log;

/**
 * @author pszcmg
 *
 */
public class FileDecoder {
	public static String TAG = "daoplayer-decoder";
	private String mPath;
	private boolean mExtractFailed = false;
	private boolean mStarted = false;
	private boolean mExtracted = false;
	private boolean mDecoded = false;
	private boolean mCancelled = false;
	int mChannels = 2;
	private int mRate;
	private Context mContext;
	Vector<short[]> mBuffers = new Vector<short[]>();

	private MediaExtractor mExtractor;
	private MediaCodec mCodec;
	private ByteBuffer [] mCodecInputBuffers;
	private ByteBuffer [] mCodecOutputBuffers;
	private MediaCodec.BufferInfo mCodecInfo;
	private boolean mSawInputEOS = false;
    private boolean mSawOutputEOS = false;

	public FileDecoder(String path, Context context) {
		this.mPath = path;
		this.mContext = context;
	}
	public synchronized void cancel() {
		mCancelled = true;
	}
	static String FILE_ASSET = "file:///android_asset/";
	private void start() {
		synchronized (this) {
			if (mExtractFailed)
				return;
			if (mStarted)
				return;
		}
		Log.d(TAG,"start decode "+mPath);
		mExtractor = new MediaExtractor();
		// asset?
		if (mPath.startsWith(FILE_ASSET)) {
			AssetManager assets = mContext.getAssets();
			try {
				AssetFileDescriptor afd = assets.openFd(mPath.substring(FILE_ASSET.length()));
				FileDescriptor fd = afd.getFileDescriptor();
				mExtractor.setDataSource(fd);
			}
			catch (Exception e) {
				Log.d(TAG,"Error opening asset "+mPath+": "+e);
				synchronized (this) {
					mExtractFailed = true;
				}
				mExtractor.release();
				mExtractor = null;
				return;
			}
		} else {
			try {
				mExtractor.setDataSource(mPath);
			} catch (IOException e) {
				Log.e(TAG,"Error creating MediaExtractor for "+mPath+": "+e);
				synchronized (this) {
					mExtractFailed = true;
				}
				mExtractor.release();
				mExtractor = null;
				return;
			}
		}
		if (mExtractor.getTrackCount()<1) {
			Log.e(TAG,"Found no tracks in "+mPath);
			synchronized (this) {
				mExtractFailed = true;
			}
			mExtractor.release();
			mExtractor = null;
			return;
		}
		Log.d(TAG,"Found "+mExtractor.getTrackCount()+" tracks");
		MediaFormat format = mExtractor.getTrackFormat(0);
		String mime = format.containsKey(MediaFormat.KEY_MIME) ? format.getString(MediaFormat.KEY_MIME): "";
		mChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT): -1;
		int fmask = format.containsKey(MediaFormat.KEY_CHANNEL_MASK) ? format.getInteger(MediaFormat.KEY_CHANNEL_MASK): -1;
		mRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE): -1;
		Log.d(TAG,"track 0: mime="+mime+" channels="+mChannels+" fmask="+fmask+" rate="+mRate);
		// E.g. 0: mime=audio/mpeg channels=2 fmask=-1 rate=44100
		if (!mime.startsWith("audio/")) {
			Log.d(TAG,"Did not find audio track 0 in "+mPath);
			synchronized (this) {
				mExtractFailed = true;
			}
			mExtractor.release();
			mExtractor = null;
			return;
		}
		if (mExtractor.getTrackCount()==1 && mime.equals("audio/mpeg")) {
			// faster??? - not at the moment
			//return decodeWithJLayer(context);
		}
		
		mExtractor.selectTrack(0);
		// see https://android.googlesource.com/platform/cts/+/jb-mr2-release/tests/tests/media/src/android/media/cts/DecoderTest.java
		mCodec = MediaCodec.createDecoderByType(mime);
		mCodec.configure(format, null /* surface */, null /* crypto */, 0 /* flags */);
		mCodec.start();
		mCodecInputBuffers = mCodec.getInputBuffers();
		mCodecOutputBuffers = mCodec.getOutputBuffers();

        //short [] decoded = new short[0];
        //int decodedIdx = 0;
        // start decoding
        mCodecInfo = new MediaCodec.BufferInfo();
        mSawInputEOS = false;
        mSawOutputEOS = false;
        
        mStarted = true;
	}
	private void getBuffers() {
		if (!mStarted)
			return;
        final long kTimeOutUs = 10000;
        int noOutputCounter = 0;
        while (!mSawOutputEOS && noOutputCounter < 50) {
        	synchronized (this) {
        		if (mCancelled) {
        			Log.d(TAG,"decode cancelled");
        			mExtractFailed = true;
        			mCodec.stop();
        			mCodec.release();
        			mCodec = null;
        			mExtractor.release();
        			mExtractor = null;
        			return;
        		}
        	}
            noOutputCounter++;
            if (!mSawInputEOS) {
                int inputBufIndex = mCodec.dequeueInputBuffer(kTimeOutUs);
                if (inputBufIndex >= 0) {
                    ByteBuffer dstBuf = mCodecInputBuffers[inputBufIndex];
                    int sampleSize =
                        mExtractor.readSampleData(dstBuf, 0 /* offset */);
                    long presentationTimeUs = 0;
                    if (sampleSize < 0) {
                        Log.d(TAG, "saw input EOS.");
                        mSawInputEOS = true;
                        sampleSize = 0;
                    } else {
                        presentationTimeUs = mExtractor.getSampleTime();
                    }
                    mCodec.queueInputBuffer(
                            inputBufIndex,
                            0 /* offset */,
                            sampleSize,
                            presentationTimeUs,
                            mSawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    if (!mSawInputEOS) {
                        mExtractor.advance();
                    }
                }
            }
            int res = mCodec.dequeueOutputBuffer(mCodecInfo, kTimeOutUs);
            if (res >= 0) {
                Log.d(TAG, "got frame, size " + mCodecInfo.size + "/" + mCodecInfo.presentationTimeUs);
                if (mCodecInfo.size > 0) {
                    noOutputCounter = 0;
                }
                int outputBufIndex = res;
                if (mCodecInfo.size > 0) {
	                ByteBuffer buf = mCodecOutputBuffers[outputBufIndex];
	                //if (decodedIdx + (info.size / 2) >= decoded.length) {
	                //    decoded = Arrays.copyOf(decoded, decodedIdx + (info.size / 2));
	                //}
	                short decoded[] = new short[mCodecInfo.size / 2];
	                int decodedIdx = 0;
	                mBuffers.add(decoded);
	                for (int i = 0; i < mCodecInfo.size; i += 2) {
	                    decoded[decodedIdx++] = buf.getShort(i);
	                }
                }
                mCodec.releaseOutputBuffer(outputBufIndex, false /* render */);
                if ((mCodecInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "saw output EOS.");
                    mSawOutputEOS = true;
                }
            } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                mCodecOutputBuffers = mCodec.getOutputBuffers();
                Log.d(TAG, "output buffers have changed.");
            } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat oformat = mCodec.getOutputFormat();
                Log.d(TAG, "output format has changed to " + oformat);
            } else {
                Log.d(TAG, "dequeueOutputBuffer returned " + res);
            }
        }
	}
	private void stop() {
		if (!mStarted)
			return;
		
        mCodec.stop();
        mCodec.release();
        mCodec = null;
        Log.d(TAG,"decoded "+mBuffers.size()+" blocks");
        
		mExtractor.release();
		mExtractor = null;

		mStarted = false;
		
		// optional?!
		tidyEnds();
		
		synchronized (this) {
			if (mSawOutputEOS)
				mExtracted = true;
		}
		return;
	}
	private int getChannels() {
		return mChannels;
	}
	private boolean decodeWithJLayer(Context context) {
		// TODO incomplete handling of output buffer data
		Log.d(TAG,"decode with JLayer "+mPath);
		// asset?
		FileInputStream fis = null;
		if (mPath.startsWith(FILE_ASSET)) {
			AssetManager assets = context.getAssets();
			try {
				AssetFileDescriptor afd = assets.openFd(mPath.substring(FILE_ASSET.length()));
				fis = afd.createInputStream();
			}
			catch (Exception e) {
				Log.d(TAG,"Error opening asset "+mPath+": "+e);
				synchronized (this) {
					mExtractFailed = true;
				}
				return false;
			}
		} else {
			try {
				fis = new FileInputStream(mPath); 
			} catch (IOException e) {
				Log.e(TAG,"Error creating MediaExtractor for "+mPath+": "+e);
				synchronized (this) {
					mExtractFailed = true;
				}
				return false;
			}
		}
		BufferedInputStream bufIn = new BufferedInputStream(fis);
		int frameCount = -1;
		Obuffer output = null;
		Decoder decoder = new Decoder(null);
		Bitstream stream = new Bitstream(bufIn);

		if (frameCount==-1)
			frameCount = Integer.MAX_VALUE;

		int frame = 0;
		long startTime = System.currentTimeMillis();

		try
		{
			for (; frame<frameCount; frame++)
			{
        		if (mCancelled) {
        			Log.d(TAG,"decode cancelled");
        			mExtractFailed = true;
        			return false;
        		}
				try
				{
					Header header = stream.readFrame();
					if (header==null)
						break;

					//progressListener.readFrame(frame, header);

					if (output==null)
					{
						// REVIEW: Incorrect functionality.
						// the decoder should provide decoded
						// frequency and channels output as it may differ from
						// the source (e.g. when downmixing stereo to mono.)
						int channels = (header.mode()==Header.SINGLE_CHANNEL) ? 1 : 2;
						int freq = header.frequency();
						output = new JLOutput(channels, freq);
						decoder.setOutputBuffer(output);
					}

					Obuffer decoderOutput = decoder.decodeFrame(header, stream);

					// REVIEW: the way the output buffer is set
					// on the decoder is a bit dodgy. Even though
					// this exception should never happen, we test to be sure.
					if (decoderOutput!=output)
						throw new InternalError("Output buffers are different.");


					//progressListener.decodedFrame(frame, header, output);

					stream.closeFrame();

				}
				catch (Exception ex)
				{
					Log.w(TAG,"error decoding with jlayer: "+ex, ex);
					synchronized (this) {
						mExtractFailed = true;
					}
					return false;
				}
			}

		}
		finally
		{

			if (output!=null)
				output.close();
		}

		int time = (int)(System.currentTimeMillis()-startTime);
		//progressListener.converterUpdate(ProgressListener.UPDATE_CONVERT_COMPLETE,
		//	time, frame);
		Log.d(TAG,"decoded in "+time+"ms: "+mPath);
		synchronized (this) {
			mExtracted = true;
		}
		return false;
	}
	static class JLOutput extends Obuffer {
		private short[] 		buffer;
		private short[] 		bufferp;
		private int 			channels;

		JLOutput(int number_of_channels, int freq) {
			buffer = new short[OBUFFERSIZE];
			bufferp = new short[MAXCHANNELS];
			channels = number_of_channels;
			
			for (int i = 0; i < number_of_channels; ++i) 
				bufferp[i] = (short)i;
		}
		@Override
		public void append(int channel, short value) {
		    buffer[bufferp[channel]] = value;
		    bufferp[channel] += channels;
		}

		@Override
		public void clear_buffer() {
		}

		@Override
		public void close() {
		}

		@Override
		public void set_stop_flag() {
		}

		@Override
		public void write_buffer(int val) {
			// TODO
			
		    for (int i = 0; i < channels; ++i) bufferp[i] = (short)i;			
		}
		
	}
	private void tidyEnds() {
		// squash up to first zero-crossing
		short lval = 0;
		doneStart:
			for (int bix = 0; bix < mBuffers.size(); bix++) {
				short [] buf = mBuffers.get(bix);
				for (int i=0; i<buf.length; i+=2) {
					short val = (short)((buf[i]+buf[i+1])/2);
					if (val==0)
						break doneStart;
					if (lval==0)
						lval = val;
					else if ((lval>0 && val<0) || (lval<0 && val>0))
						break doneStart;
					buf[i] = buf[i+1] = 0;
				}
			}
		lval = 0;
		doneEnd:
			for (int bix = mBuffers.size()-1; bix>=0; bix--) {
				short [] buf = mBuffers.get(bix);
				for (int i=buf.length-2; i>=0; i-=2) {
					short val = (short)((buf[i]+buf[i+1])/2);
					if (val==0)
						break doneEnd;
					if (lval==0)
						lval = val;
					else if ((lval>0 && val<0) || (lval<0 && val>0))
						break doneEnd;
					buf[i] = buf[i+1] = 0;
				}
			}
	}
	/* getLength() not supported for on-the-fly decode */
	public synchronized boolean isExtracted() {
		return mExtracted;
	}
	private class DecodeTask extends AsyncTask<Context,Integer,Boolean> {

		@Override
		protected Boolean doInBackground(Context... params) {
			FileDecoder.this.start();
			FileDecoder.this.getBuffers();
			FileDecoder.this.stop();
			return true;
		}

		/* (non-Javadoc)
		 * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
		 */
		@Override
		protected void onPostExecute(Boolean result) {
			//Toast.makeText(context, "Decoded "+mPath, Toast.LENGTH_SHORT).show();
			super.onPostExecute(result);
		}
		
	}
	public void queueDecode() {
		DecodeTask t = new DecodeTask();
		t.execute();
	}
}
