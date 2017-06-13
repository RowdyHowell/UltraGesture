package edu.smu.lyle.ultragesture;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.OperationCanceledException;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.samsung.android.sdk.SsdkUnsupportedException;
import com.samsung.android.sdk.gesture.Sgesture;
import com.samsung.android.sdk.gesture.SgestureHand;
import com.samsung.android.sdk.gesture.SgestureHand.ChangeListener;
import com.samsung.android.sdk.gesture.SgestureHand.Info;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import butterknife.BindString;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import edu.samsung.ultragesture.R;

public class UltraGesture extends Activity implements ChangeListener {

    private static final String TAG = "UltraGesture";

    @BindView(R.id.start_stop_button)
    Button mStartButton;
    @BindView(R.id.restart_button)
    Button mRestartButton;

    @BindView(R.id.user_id)
    EditText mUserText;
    @BindView(R.id.trial_id)
    TextView mTrialText;

    @BindView(R.id.gesture_title)
    TextView mGestureText;
    @BindView(R.id.gesture_description)
    TextView mDescText;
    @BindView(R.id.countdown)
    TextView mCountdownText;

    @BindView(R.id.gesture_speed)
    TextView mGestureSpeed;
    @BindView(R.id.gesture_angle)
    TextView mGestureAngle;

    @BindString(R.string.gesture_name_generic)
    String GESTURE_NAME_GENERIC;
    @BindString(R.string.gesture_desc_generic)
    String GESTURE_DESCRIPTION_GENERIC;
    @BindString(R.string.go_string)
    String GO_STRING;

    private TestThread mTestThread;

    private Condition condition = Condition.INACTIVE;

    private SgestureHand mSGestureHand = null;
    private int mLastSpeed = -1;
    private int mLastAngle = -1;

    Storage mStorage = new Storage();
    TrialIdentity mIdentity;

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message inputMessage) {
            //Get the current snapshot
            TestSnapshot snapshot = (TestSnapshot) inputMessage.obj;

            //Display info
            mGestureText.setText(snapshot.currentGesture.getName());
            mDescText.setText(snapshot.currentGesture.getDesc());

            if (snapshot.countdownTime > 0) {
                mCountdownText.setText(Integer.toString((int) Math.ceil(snapshot.countdownTime / 1000)));
            } else if (snapshot.countdownTime == 0) {
                mCountdownText.setText(GO_STRING);
            } else if (snapshot.countdownTime == -1) {
                if (snapshot.type == MessageType.FAILURE)
                    mCountdownText.setText("Try again.");
                else
                    mCountdownText.setText(R.string.done_string);
            }

            //Check doneWithAllGestures
            if (snapshot.type == MessageType.ALL_DONE) {
                stopTest(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_ultra_gesture);
        ButterKnife.bind(this);

        mIdentity = new TrialIdentity(this);

        Permissions.verifyAllPermissions(this);

        //Set the volume
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, maxVol);

        //Create gesture
        Sgesture gesture = new Sgesture();
        try {
            gesture.initialize(this);
        } catch (SsdkUnsupportedException e) {
            Log.e(TAG, "Couldn't load Samsung SGesture", e);
            throw new RuntimeException(e);
        }

        //Create gesture hand
        mSGestureHand = new SgestureHand(Looper.getMainLooper(), gesture);
    }

    @Override
    protected void onPause() {
        super.onPause();

        //Stop the test on pause
        pauseTest();
    }

    private void updateUI() {
        switch (condition) {
            case ACTIVE:
                mStartButton.setText("pause");
                mStartButton.setBackgroundColor(getResources().getColor(R.color.g_yellow));
                mRestartButton.setEnabled(false);
                break;
            case INACTIVE:
                mStartButton.setText("start");
                mStartButton.setBackgroundColor(getResources().getColor(R.color.g_blue));
                mRestartButton.setEnabled(false);
                break;
            case PAUSED:
                mStartButton.setText("resume");
                mStartButton.setBackgroundColor(getResources().getColor(R.color.g_blue));
                mRestartButton.setEnabled(true);
                break;
        }
    }

	/*
	 * Test controls
	 */

    private void startTest() {
        //Update state
        condition = Condition.ACTIVE;
        updateUI();

        //Start gesture listener
        mSGestureHand.start(0, this);

        //Create thread
        mTestThread = new TestThread();
        mTestThread.start();
    }

    private void resumeTest() {
        //Update state
        condition = Condition.ACTIVE;
        updateUI();
    }

    private void pauseTest() {
        //Update state
        condition = Condition.PAUSED;
        updateUI();
    }

    private void stopTest(boolean save) {
        //Update state
        condition = Condition.INACTIVE;
        updateUI();

        //Stop listening to gestures
        mSGestureHand.stop();
        mLastSpeed = -1;
        mLastAngle = -1;

        //Null the test thread
        mTestThread = null;

        //Check save
        if (save) {
            //Increment test to next
            mIdentity.incrementTrialNumber();

            mGestureText.setText("Finished!");
            mDescText.setText("Thank you for participating.");
        } else {
            mGestureText.setText(GESTURE_NAME_GENERIC);
            mDescText.setText(GESTURE_DESCRIPTION_GENERIC);
            mCountdownText.setText(GO_STRING);
        }
    }

    @OnClick(R.id.start_stop_button)
    void start_or_stop_or_pause() {
        switch (condition) {
            case INACTIVE:
                startTest();
                break;
            case PAUSED:
                resumeTest();
                break;
            case ACTIVE:
                pauseTest();
                break;
        }
    }

    @OnClick(R.id.restart_button)
    void restart_button() {
        stopTest(false);
    }

    @Override
    public void onChanged(Info info) {
        //Set the gesture data here
        mLastSpeed = info.getSpeed();
        mLastAngle = info.getAngle();

        Locale locale = Locale.getDefault();
        mGestureSpeed.setText(String.format(locale, "Gesture Speed: %d", mLastSpeed));
        mGestureAngle.setText(String.format(locale, "Gesture Angle: %d", mLastAngle));
    }

    private class TestThread extends Thread {

        private final long COUNTDOWN_MILLISECONDS = 3000L;
        FrequencyEmitter emitter;
        private final List<Gesture> mGestures;

        TestThread() {
            //Get list of gestures
            mGestures = Gesture.getGestures(UltraGesture.this);
            Collections.shuffle(mGestures);

            emitter = new FrequencyEmitter();
        }

        private void sendMessage(Gesture g, long time, MessageType type) {
            Log.v(TAG, "Test update: " + g.getName() + " at " + time);
            mHandler.obtainMessage(0, new TestSnapshot(g, time, type)).sendToTarget();
        }

        void discoverGestures() {
            //Loop for each gesture
            int trialID = mIdentity.getTrialNumber();
            Log.d(TAG, "Trial ID " + trialID);
            final String trialLabel = "Trial number " + trialID;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mTrialText.setText(trialLabel);
                }
            });
            for (final Gesture gesture : mGestures) {
                //Generate filename, file, and writer
                File rawFile = mStorage.getFile(mUserText.getText().toString(), Integer.toString(trialID), gesture);

                ArrayList<Movement> movements = new ArrayList<>();
                int numSamples = 0;
                long lengthOfRecord = 0;

                boolean didDetectGesture = false;
                while (!didDetectGesture) {
                    // Send initial message.
                    sendMessage(gesture, COUNTDOWN_MILLISECONDS, MessageType.SUCCESS);
                    displayInstructions(gesture);

                    // Start emitting frequency.
                    emitter.start();
                    Log.d(TAG, "Emitter started!");

                    numSamples = 0;

                    // Reset gesture data.
                    movements = new ArrayList<>();
                    mLastAngle = mLastSpeed = -1;

                    lengthOfRecord = 0;

                    // Start writing to file.
                    try (final DataOutputStream rawWriter = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(rawFile)))) {

                        // Write header
                        rawWriter.writeByte(0); //Revision
                        rawWriter.writeInt(AudioPoller.SAMPLE_RATE); //Sample rate
                        rawWriter.writeInt(FrequencyEmitter.FREQUENCY); //Frequency 1
                        rawWriter.writeInt(0); //Frequency 2
                        rawWriter.writeInt(0); //Number of audio samples (will rewrite later)
                        rawWriter.writeByte(0); //Number of direction samples
                        rawWriter.writeLong(0); //Length of audio sample (in nanoseconds)

                        //Create the audio recorder
                        int bufferSize = AudioRecord.getMinBufferSize(AudioPoller.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        AudioRecord mAudioRecord = new AudioRecord(AudioSource.MIC, AudioPoller.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                        mAudioRecord.startRecording();

                        //Create and start the audio poller
                        short pcmData[] = new short[512];
                        int state;
                        long startTime = System.nanoTime();
                        while (startTime + 3000000000L > System.nanoTime()) {
                            //Read in data
                            state = mAudioRecord.read(pcmData, 0, 512);
                            Log.i(TAG, String.valueOf(mAudioRecord.getSampleRate()));

                            if (state == AudioRecord.ERROR_INVALID_OPERATION)
                                Log.e(TAG, "Invalid operation");
                            else if (state == AudioRecord.ERROR_BAD_VALUE)
                                Log.e(TAG, "Invalid arguments");
                            else if (state < 512)
                                Log.e(TAG, "Buffer not filled");

                            //Write data to file
                            for (int x = 0; x < state; x++)
                                rawWriter.writeShort(pcmData[x]);

                            //Increment count
                            numSamples += state;

                            if (mLastSpeed != -1) {
                                Log.v(TAG, "Gesture detected");

                                //Add data to list with timestamp
                                movements.add(new Movement(numSamples, mLastSpeed, mLastAngle));

                                //Reset gesture
                                mLastAngle = mLastSpeed = -1;
                            }
                        }


                        //Get the length of the recording
                        lengthOfRecord = System.nanoTime() - startTime;

                        //Stop recording
                        mAudioRecord.stop();
                        mAudioRecord.release();

                        //Stop emitting signal
                        emitter.stop();
                        Log.d(TAG, "Emitter stopped!");

                        if (movements.size() == 0) {
                            sendMessage(gesture, -1, MessageType.FAILURE);
                            try {
                                Thread.sleep(2000L);
                            } catch (InterruptedException e) {
                                /* No need to die because of this. */
                            }
                            continue;
                        }
                        didDetectGesture = true;


                        //Write footer (sgesture movements)
                        for (int x = 0; x < movements.size(); x += 3) {
                            rawWriter.writeInt(movements.get(x).index);  //Sample index
                            rawWriter.writeInt(movements.get(x).speed);    //speed
                            rawWriter.writeInt(movements.get(x).angle);  //angle
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "IOException in writing to file.");
                        Log.e(TAG, e.getMessage());
                        System.exit(-1);
                    }
                }

                updateHeader(rawFile, numSamples, movements, lengthOfRecord);

                //Send doneWithAllGestures signal
                sendMessage(gesture, -1, MessageType.SUCCESS);

                //Wait a second to start next test
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    /* No need to die because of this. */
                }
            }

            //Done!
            if (condition != Condition.INACTIVE)
                sendMessage(mGestures.get(mGestures.size() - 1), -1, MessageType.ALL_DONE);
        }

        @Override
        public void run() {
            try {
                discoverGestures();
            } catch (OperationCanceledException e) {
                // pass
            }
        }

        private void updateHeader(File rawFile, int numSamples, ArrayList<Movement> gestures, long lengthOfRecord) {
            try (FileChannel headerUpdater = new RandomAccessFile(rawFile, "rw").getChannel()) {
                //Rewrite number of audio samples
                headerUpdater.position(13);
                headerUpdater.write(ByteBuffer.wrap(new byte[]{
                        (byte) (numSamples >> 24),        //Number of samples
                        (byte) (numSamples >> 16),
                        (byte) (numSamples >> 8),
                        (byte) (numSamples >> 0),
                        (byte) (gestures.size()),    //Number of direction samples
                        (byte) (lengthOfRecord >> 56),   //Length of sample in nanoseconds
                        (byte) (lengthOfRecord >> 48),
                        (byte) (lengthOfRecord >> 40),
                        (byte) (lengthOfRecord >> 32),
                        (byte) (lengthOfRecord >> 24),
                        (byte) (lengthOfRecord >> 16),
                        (byte) (lengthOfRecord >> 8),
                        (byte) (lengthOfRecord >> 0)
                }));
                headerUpdater.close();

            } catch (IOException e) {
                Log.e(TAG, "Couldn't update file header.");
                System.exit(-2);
            }
        }

        private void displayInstructions(Gesture gesture) {
            //Start countdown
            long goalTime = System.currentTimeMillis() + COUNTDOWN_MILLISECONDS;
            long nextGoal = COUNTDOWN_MILLISECONDS - 1000L;

            long remainingTime;
            boolean lastPaused = false;
            while (nextGoal >= 0) {
                //Cancel logic
                if (condition == Condition.INACTIVE) {
                    throw new OperationCanceledException();
                }

                //Pausing logic
                if (condition == Condition.PAUSED) {
                    goalTime = System.currentTimeMillis() + COUNTDOWN_MILLISECONDS;
                    nextGoal = COUNTDOWN_MILLISECONDS - 1000L;

                    if (!lastPaused)
                        sendMessage(gesture, COUNTDOWN_MILLISECONDS, MessageType.SUCCESS);

                    lastPaused = true;
                    continue;
                } else {
                    lastPaused = false;
                }

                //Get the current time remaining
                remainingTime = goalTime - System.currentTimeMillis();
                if (remainingTime < nextGoal) {
                    sendMessage(gesture, nextGoal, MessageType.SUCCESS);
                    nextGoal -= 1000L;
                }
            }
        }
    }

    private class TestSnapshot {
        final Gesture currentGesture;
        final long countdownTime;
        MessageType type;

        TestSnapshot(Gesture g, long time, MessageType type) {
            currentGesture = g;
            countdownTime = time;
            this.type = type;
        }
    }
}