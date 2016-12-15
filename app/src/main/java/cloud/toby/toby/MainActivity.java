package cloud.toby.toby;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cloud.toby.NotConnectedException;


public class MainActivity extends AppCompatActivity {

    private final static String PREFERENCES = "Toby.Preferences";
    private BotService mBot;
    private boolean mBound;
    private MorseParser mMorseParser;


    @Override
    protected void onStart() {
        System.out.println("MainActivity onStart()");
        super.onStart();
        // bind to BotService
        Intent intent = new Intent(this, BotService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // unbind from BotService
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final AutoCompleteTextView messageInput = (AutoCompleteTextView) findViewById(R.id.messageInput);
        Button sendButton = (Button) findViewById(R.id.sendButton);
        Button cameraButton = (Button) findViewById(R.id.cameraButton);
        Button listenButton = (Button) findViewById(R.id.listenButton);
        Button killButton = (Button) findViewById(R.id.killButton);

        sendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!mBot.isConnected()) startLoginActivity();

                JSONObject payload = new JSONObject();

                // get text input
                String message = messageInput.getText().toString();
                System.out.println("Message:" + message);

                // get ack tag from text, if any
                List<String> ackTags = extractTags(message, "&");
                String ack = "";
                if (!ackTags.isEmpty()) ack = ackTags.get(0);

                try {
                    payload.put("message", removeTags(removeTags(message, "#"),"&"));
                    mBot.send(payload, extractTags(message, "#"), ack);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });

        cameraButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!mBot.isConnected()) startLoginActivity();
                mBot.takePicture(10);
            }
        });

        listenButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!mBot.isConnected()) startLoginActivity();
                mBot.listen();
            }
        });

        killButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!mBot.isConnected()) startLoginActivity();
                mBot.kill();
            }
        });
    }

    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            BotService.LocalBinder binder = (BotService.LocalBinder) service;
            mBot = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    /**
     * Start the login activity.
     */
    private void startLoginActivity() {
        Intent startLoginActivityIntent = new Intent(getApplicationContext(), LoginActivity.class);
        startActivity(startLoginActivityIntent);
    }

    /**
     * Extract tags from text with a given delimiter
     * @param text
     * @param delimiter
     * @return a list of tags found in the text
     */
    private static List<String> extractTags(String text, String delimiter) {
        Pattern p = Pattern.compile(String.format("(?:^|\\s|[\\p{Punct}&&[^/]])(%s[\\p{L}0-9-_]+)", new Object[]{delimiter}));
        Matcher m = p.matcher(text);
        ArrayList tags = new ArrayList();
        while(m.find()) {
            tags.add(m.group().trim().substring(1));
        }
        return tags;
    }

    /**
     * Remove tags from text with a given delimiter
     * @param text
     * @param delimiter
     * @return the text with tags removed
     */
    private static String removeTags(String text, String delimiter) {
        return text.replaceAll(String.format("(?:^|\\s|[\\p{Punct}&&[^/]])(%s[\\p{L}0-9-_]+)", new Object[]{delimiter}), "").trim();
    }
}
