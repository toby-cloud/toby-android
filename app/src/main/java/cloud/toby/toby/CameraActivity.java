package cloud.toby.toby;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;


public class CameraActivity extends AppCompatActivity {

    static final int REQUEST_IMAGE_CAPTURE = 1;
     private static final String EXTRA_QUALITY  = "cloud.toby.toby.extra.QUALITY";

    String mImagePath;
    BotService mService;
    boolean mBound = false;
    private int quality;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        dispatchTakePictureIntent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // bind to BotService
        Intent intent = new Intent(this, BotService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        this.quality = getIntent().getIntExtra(EXTRA_QUALITY, 10);
        System.out.println("activity set to " + quality);
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


    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );

        // Save a file: path for use with ACTION_VIEW intents
        mImagePath = image.getAbsolutePath();
        return image;
    }

    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            // Create the File where the photo should go
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException e) {
                // Error occurred while creating the File
                e.printStackTrace();
            }
            // Continue only if the File was successfully created
            if (photoFile != null) {
                Uri photoURI = FileProvider.getUriForFile(this,
                        "com.example.android.fileprovider",
                        photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }
        }
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {

            BitmapFactory.Options bmOptions = new BitmapFactory.Options();
            Bitmap imageBitmap = BitmapFactory.decodeFile(mImagePath, bmOptions);

            // if error decoding
            if (imageBitmap == null) {
                JSONObject payload = new JSONObject();
                try {
                    payload.put("message", "error decoding image");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                mService.send(payload, new ArrayList<>(), "");
                finish();
            }

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            imageBitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();

            String encodedImage = Base64.encodeToString(byteArray, Base64.DEFAULT);

            if (encodedImage != null)
                mService.sendPhoto(encodedImage);
            else
                System.out.println("photo is null");
        }

        finish();
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
