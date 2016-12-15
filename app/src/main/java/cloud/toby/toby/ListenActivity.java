package cloud.toby.toby;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.speech.RecognizerIntent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.telecom.Call;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class ListenActivity extends AppCompatActivity  {

    BotService mService;
    boolean mBound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //Remove title bar
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);

        //Remove notification bar
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_listen);

    }

    @Override
    protected void onStart() {
        super.onStart();
        // bind to BotService
        Intent intent = new Intent(this, BotService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        listen();
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


    public void listen() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening");
        intent.putExtra(RecognizerIntent.EXTRA_SECURE, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, 1);
        try {
            startActivityForResult(intent, 100);
        } catch (ActivityNotFoundException a) {
            Toast.makeText(getApplicationContext(), "Speech not supported", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Receiving speech input
     * */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        System.out.println("received result " + data);

        switch (requestCode) {
            case 100: {
                if (resultCode == RESULT_OK && null != data) {
                    System.out.println(data.toString());
                    ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    System.out.println("result: " + result.toString());

                    // execute callback response in service
                    mService.executeListenCallback(result.get(0).toString());
                    finish();
                }
                break;
            }
            default: {
                mService.respond(null, "didn't understand");
                finish();
                break;
            }
        }
    }

    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            BotService.LocalBinder binder = (BotService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };


}
