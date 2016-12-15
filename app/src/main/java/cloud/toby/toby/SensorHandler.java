package cloud.toby.toby;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * Created by Gabe on 12/7/16.
 */

public class SensorHandler implements SensorEventListener {

    private SensorManager mSensorManager;
    private Sensor mSensor;
    private OnSensorEventListener mListener;

    public SensorHandler(SensorManager sensorManager, Sensor sensor) {
        this.mSensorManager = sensorManager;
        this.mSensor = sensor;

    }

    public interface OnSensorEventListener {
        void onSensorEvent(SensorEvent event);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        this.mListener.onSensorEvent(event);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // do nothing
    }

    /**
     * Listen for sensor events
     * @param listener
     */
    public void listen(OnSensorEventListener listener) {
        this.mListener = listener;
        mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    /**
     * Unregister sensor listener.
     */
    public void kill() {
        mSensorManager.unregisterListener(this);
    }


}
