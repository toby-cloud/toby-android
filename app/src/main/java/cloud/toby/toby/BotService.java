package cloud.toby.toby;

import android.app.IntentService;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;
import android.widget.Toast;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cloud.toby.Bot;
import cloud.toby.Message;
import cloud.toby.NotConnectedException;
import cloud.toby.OnConnectCallback;
import cloud.toby.OnDisconnectCallback;
import cloud.toby.OnMessageCallback;

/**
 * Created by Gabe on 11/6/16.
 */
public class BotService extends Service {

    private static final String PREFERENCES = "Toby.Preferences";
    private static final String ACTION_TOBY_CONNECT = "io.ekho.ekho.action.TOBY_CONNECT";
    private static final String EXTRA_BOTID  = "io.ekho.ekho.extra.BOTID";
    private static final String EXTRA_SECRET = "io.ekho.ekho.extra.SECRET";
    private final IBinder mBinder = new LocalBinder();
    private Bot mBot;
    private TextToSpeech tts;
    private CallbackInterface mListenCallback;
    private boolean killed = false;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock wakeLock;

    // The following are used for sensors
    private SensorManager mSensorManager;
    private SensorHandler mLightSensorHandler;

    // used by shake detector
    private Sensor mAccelerometer;
    private ShakeDetector mShakeDetector;

    public BotService() {}

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        BotService getService() {
            return BotService.this; // return instance of service so clients can call public methods
        }
    }

    /**
     * Called when we establish a Toby connection;
     */
    private class OnConnect implements OnConnectCallback {
        public void go(Bot b) {
            System.out.println("Connected!");

            // acquire partial wake lock to keep cpu running
            mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TobyWakelockTag");
            wakeLock.acquire();

            mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

            // start main activity
            Intent startMainActivityIntent = new Intent(getApplicationContext(), MainActivity.class);
            startMainActivityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(startMainActivityIntent);

            // listen for incoming sms
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.provider.Telephony.SMS_RECEIVED");
            //registerReceiver(receiver, filter);


            tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        int result = tts.setLanguage(Locale.US);
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.e("TTS", "This Language is not supported");
                        }
                        speak("Connected to Toby.");
                    } else {
                        Log.e("TTS", "Initialization Failed!");
                    }
                }
            });
        }
    }

    private void startLightSensor() {
        Sensor lightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mLightSensorHandler = new SensorHandler(mSensorManager, lightSensor);
        mLightSensorHandler.listen(new SensorHandler.OnSensorEventListener() {
            @Override
            public void onSensorEvent(SensorEvent event) {
                System.out.println("Light sensor: " + event.values[0]);
                JSONObject payload = new JSONObject();
                try {
                    payload.put("message", "" + event.values[0]);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                send(payload, Arrays.asList("lightSensor"), "");
            }
        });
    }
    private void killLightSensor() {
        if (mLightSensorHandler != null)
            mLightSensorHandler.kill();
    }

    private void initiateShakeDetector() {
        // ShakeDetector initialization
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mShakeDetector = new ShakeDetector();
        mShakeDetector.setOnShakeListener(new ShakeDetector.OnShakeListener() {

            @Override
            public void onShake(int count) {
                System.out.println("SHAKE DETECTED! " + count);
                if (mBot.isConnected()) {
                    respond(null, "shake " + count);
                    if (count == 3) {
                        JSONObject payload = new JSONObject();
                        try {
                            payload.put("message", "switch");
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        send(payload, Arrays.asList("light"), "");
                    }
                }
            }
        });
        mSensorManager.registerListener(mShakeDetector, mAccelerometer,	SensorManager.SENSOR_DELAY_UI);
    }

    private void killShakeDetector() {
        if (mShakeDetector != null)
            mSensorManager.unregisterListener(mShakeDetector);
    }

    public void kill () {
        die();
        killed = true;
    }

    /**
     * Called when we are disconnected from Toby;
     */
    public class OnDisconnect implements OnDisconnectCallback {
        public void go() {
            wakeLock.release();
            killLightSensor();
            killShakeDetector();

            System.out.println("Disconnected!");
            speak("Disconnected from Toby");

            if (killed) {
                System.out.println("mBot was killed. don't reconnect");
                return;
            }

            // restart after 1 sec delay
            delay(1000);
            System.out.println("restarting...");
            mBot.start();
        }
    }


    /**
     * Extract the command from text. AKA the first word
     * @param text
     * @return
     */
    private String extractCommand(String text) {
        if (text.isEmpty()) return "";
        return text.split(" ")[0].trim();
    }

    /**
     * Extract string arguments from text. AKA all the text after the command
     * @param text
     * @return
     */
    private String extractArguments(String text) {
        if (text.isEmpty()) return "";

        String[] split = text.split(" ");
        String[] argumentList = Arrays.copyOfRange(split, 1, split.length);

        StringBuilder builder = new StringBuilder();
        for(String s : argumentList) {
            builder.append(s + " ");
        }
        return builder.toString().trim();
    }


    /**
     * Called when we receive a server message;
     */
    public class OnMessage implements OnMessageCallback {

        public void go(Bot b, final Message message) {

            JSONObject payload = message.getPayload();
            String m = "";

            try {
                m = payload.getString("message");
            } catch (JSONException e) {
                e.printStackTrace();
            }

            final String command = extractCommand(m);
            final String arguments = extractArguments(m);

            // Speak on device
            if (command.equals("tts") || command.equals("speak") || command.equals("say")) {
                speak(arguments);
                respond(message, "spoken");

            // Ask a question
            } else if (command.equals("ask")) {
                respond(message, "asking");
                ask(m.substring(4), new CallbackInterface() {
                    @Override
                    public void execute(String result) {
                        respond(message, "answer: " + result);
                    }
                });

            // Listen
            } else if (command.equals("listen")) {
                listen();

            // Set device volume
            } else if (command.equals("vol") || command.equals("volume")) {
                if (!arguments.isEmpty()) {
                    setVolume(Integer.parseInt(arguments));
                    respond(message, "volume set");
                    speak("volume set to " + Integer.parseInt(arguments));
                }

            // Copy to clipboard
            } else if (command.equals("copy")) {
                if (!arguments.isEmpty()) {
                    copyToClipboard(arguments);
                    speak("copied to clipboard");
                    respond(message, "copied");
                }

            // Camera
            } else if (command.equals("cam") || command.equals("camera")) {
                int q = 10;
                if (!arguments.isEmpty()) {
                    try {
                        q = Integer.parseInt(arguments);
                        if (q < 0 || q > 100) q = 10;
                    } catch (Exception e) {
                        q = 10;
                    }
                }
                respond(message, "taking picture with quality " + q);
                takePicture(q);

            // Shake detector on
            } else if (m.equals("sense shake")) {
                initiateShakeDetector();
                respond(message, "shake detector initiated");

            // Shake detector off
            } else if (m.equals("shake off")) {
                killShakeDetector();
                respond(message, "shake detector disabled");

            // Light sensor on
            } else if (m.equals("sense light")) {
                startLightSensor();
                respond(message, "light sensor is on");

            // Light sensor off
            } else if (m.equals("light sensor off")) {
                killLightSensor();
                respond(message, "light detector disabled");

            // Ping
            } else if (m.equals("ping")) {
                respond(message, "pong");
                speak("pong");

            // ???
            } else {
                System.out.println(m);
                respond(message, "what? " + m);
            }
        }
    }


    protected void onHandleIntent(Intent intent) {
        System.out.println("onHandleIntent");
        if (intent != null) {
            final String action = intent.getAction();
            if (ACTION_TOBY_CONNECT.equals(action)) {
                final String id = intent.getStringExtra(EXTRA_BOTID);
                final String sk = intent.getStringExtra(EXTRA_SECRET);
                handleActionTobyConnect(id, sk);

                // Save credentials for reconnect
                SharedPreferences settings = getSharedPreferences(PREFERENCES, 0);
                final SharedPreferences.Editor editor = settings.edit();

                editor.putString("id", id);
                editor.putString("sk", sk);
                editor.apply();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Toast.makeText(this, "toby service starting", Toast.LENGTH_SHORT).show();

        if (mBot != null) {
            System.out.println("Bot is already connected!");
        }

        if (intent == null) {
            // restore preferences
            SharedPreferences settings = getSharedPreferences(PREFERENCES, 0);
            String id = settings.getString("id", "");
            String sk = settings.getString("sk", "");

            startActionTobyConnect(getApplicationContext(), id, sk);
            return START_STICKY;
        }

        onHandleIntent(intent);
        return START_STICKY;
    }
    @Override
    public void onDestroy() {
        System.out.println("Toby service destroyed");
        Toast.makeText(this, "toby service destroyed", Toast.LENGTH_SHORT).show();
        if (mLightSensorHandler != null) mLightSensorHandler.kill();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Starts this service to perform action MQTT_CONNECT with the given parameters. If
     * the service is already performing a task this action will be queued.
     *
     * @see IntentService
     */
    public static void startActionTobyConnect(Context context, String botId, String secret) {
        Intent intent = new Intent(context, BotService.class);
        intent.setAction(ACTION_TOBY_CONNECT);
        intent.putExtra(EXTRA_BOTID, botId);
        intent.putExtra(EXTRA_SECRET, secret);
        context.startService(intent);
    }

    /**
     * Handle action MQTT_CONNECT in the provided background thread with the provided
     * parameters.
     */
    private void handleActionTobyConnect(String botId, String secret) {
        if (this.mBot == null)
            this.mBot = new Bot(botId, secret, new OnConnect(), new OnDisconnect(), new OnMessage());

        if (!this.mBot.isConnected())
            this.mBot.start();
    }

    public boolean isConnected() {
        return mBot != null && mBot.isConnected();
    }

    /**
     * Kill mBot service.
     */
    public void die() {
        Intent startLoginActivityIntent = new Intent(getApplicationContext(), LoginActivity.class);
        startLoginActivityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(startLoginActivityIntent);
        killed = true;
        if (mBot != null)
            mBot.end();
        stopSelf();
    }

    /**
     * Delay execution
     * @param millis
     */
    private void delay(long millis) {
        Object lock = new Object();
        synchronized (lock) {
            try {
                lock.wait(millis);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Respond to a received message
     * @param m
     * @param text
     */
    public void respond(Message m, String text) {
        JSONObject payload = new JSONObject();
        ArrayList<String> tags = new ArrayList<>();
        try {
            payload.put("message", text);
            if (m != null && m.getAck() != null && m.getAck().length() > 0) {
                tags.add(m.getAck());
            }
            mBot.send(payload, tags, "");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Send a message (proxy for mBot send)
     * @param payload
     * @param tags
     * @param ack
     */
    public void send(JSONObject payload, List tags, String ack) {
        try {
            mBot.send(payload, tags, ack);
        } catch (NotConnectedException e) {
            e.printStackTrace();
            mBot.start();
        }
    }

    /**
     * Send a photo as a message
     * @param base64 the base64 encoded png data
     */
    public void sendPhoto(String base64) {
        JSONObject payload = new JSONObject();
        ArrayList<String> tags = new ArrayList<>();
        try {
            payload.put("png", base64);
            tags.add("png");
            mBot.send(payload, tags, "");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Copy text to the device's clipboard.
     * @param text
     */
    private void copyToClipboard(String text) {
        Looper.prepare();
        System.out.println("attempting to copy");
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("label", text);
        clipboard.setPrimaryClip(clip);
    }

    /**
     * Speak text on the device.
     * @param text
     */
    private void speak(String text) {
        if (text.length() < 1 || tts == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }else{
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    /**
     * Speak a question, then listen for the answer and execute callback with result.
     * @param question the question to speak
     * @param callback the callback to be executed with the result
     */
    private void ask(String question, CallbackInterface callback) {
        if (question.length() < 5) return;
        this.speak(question);
        while (tts.isSpeaking()) {
            // wait until done speaking
        }
        Object lock = new Object();
        synchronized (lock) {
            try {
                lock.wait(100);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        setListenCallback(callback);
        listen();
    }

    private void setListenCallback(CallbackInterface callback) {
        this.mListenCallback = callback;
    }

    private boolean hasListenCallback() {
        return mListenCallback != null;
    }


    public void executeListenCallback(String result) {
        Object lock = new Object();
        synchronized (lock) {
            try {
                lock.wait(150);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (hasListenCallback()) {
            this.mListenCallback.execute(result);
            this.mListenCallback = null;
        }
    }


    public void listen() {
        if (this.mListenCallback == null) {
            this.setListenCallback(new CallbackInterface() {
                @Override
                public void execute(String result) {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("message", result);
                        mBot.send(payload, Arrays.asList(""), "");
                    } catch (JSONException e) {
                        e.printStackTrace();
                    } catch (NotConnectedException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
        Intent intent = new Intent(this, ListenActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * Take a picture using default camera intent.
     * @param quality integer between 0 and 100
     */
    public void takePicture(int quality) {
        System.out.println("trying to take pic with quality " + quality);
        final String EXTRA_QUALITY  = "cloud.toby.toby.extra.QUALITY";
        Intent intent = new Intent(this, CameraActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_QUALITY, quality);
        startActivity(intent);
    }

    /**
     * Set the device's speaker volume.
     * @param level integer between 0 and 15
     */
    private void setVolume(int level) {
        if (level < 0) level = 0;
        if (level > 15) level = 15;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
    }


    /**
     * Send an SMS from the device.
     * @param recipient
     * @param body
     */
    private void sendSMS(String recipient, String body) {
        if (recipient.length() > 0 && body.length() > 0) {
            System.out.println("sending sms");
            SmsManager sm = SmsManager.getDefault();
            sm.sendTextMessage(recipient, null, body, null, null);
        }
    }

    /**
     * Look up a contact's name by phone number
     * @param context
     * @param phoneNumber
     * @return
     */
    public static String getContactName(Context context, String phoneNumber) {
        ContentResolver cr = context.getContentResolver();
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        Cursor cursor = cr.query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
        if (cursor == null) {
            return null;
        }
        String contactName = null;
        if(cursor.moveToFirst()) {
            contactName = cursor.getString(cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME));
        }

        if(cursor != null && !cursor.isClosed()) {
            cursor.close();
        }

        return contactName;
    }

    private static List<String> extractTags(String text, String delimiter) {
        Pattern p = Pattern.compile(String.format("(?:^|\\s|[\\p{Punct}&&[^/]])(%s[\\p{L}0-9-_]+)", new Object[]{delimiter}));
        Matcher m = p.matcher(text);
        List<String> tags = new ArrayList<>();

        while(m.find()) {
            tags.add(m.group().trim().substring(1));
        }

        return tags;
    }

    private static String removeTags(String text, String delimiter) {
        return text.replaceAll(String.format("(?:^|\\s|[\\p{Punct}&&[^/]])(%s[\\p{L}0-9-_]+)", new Object[]{delimiter}), "").trim();
    }

    // Incoming SMS Receiver
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            System.out.println("receiving incoming sms");

            Bundle intentExtras = intent.getExtras();
            Object[] sms = (Object[]) intentExtras.get("pdus");
            String smsMessageStr = "";
            String sender = "";

            for (int i = 0; i < sms.length; ++i) {
                SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) sms[i]);
                String smsBody = smsMessage.getMessageBody();
                sender = smsMessage.getOriginatingAddress();
                smsMessageStr += smsBody + "\n";
            }

            JSONObject payload = new JSONObject();
            JSONObject receive = new JSONObject();
            try {
                receive.put("sender", sender);
                receive.put("message", smsMessageStr);
                payload.put("receive", receive);
            } catch (JSONException e) {
                e.printStackTrace();
            }

            try {
                mBot.send(payload, new ArrayList<String>(), "");
            } catch (NotConnectedException e) {
                e.printStackTrace();
            }
        }

    };

}
