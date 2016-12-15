package cloud.toby.toby;

import android.content.Context;

import java.util.ArrayList;



/**
 * Created by Gabe on 2/28/16.
 */
public class MorseParser {

    private String clickCommand;
    private ArrayList<Long> down_times;
    private ArrayList<Long> up_times;
    private Thread timeout_thread;
    private CallbackInterface cb;


    // Constructor
    public MorseParser(CallbackInterface cb) {
        this.cb = cb;
        this.down_times = new ArrayList<Long>();
        this.up_times = new ArrayList<Long>();
        this.clickCommand = "";
    }


    public void reset() {
        send();
        System.out.println(clickCommand);
        this.clickCommand = "";
        this.down_times = new ArrayList<Long>();
        this.up_times = new ArrayList<Long>();
    }

    public void send() {
        System.out.println("Click command: " + clickCommand);
        cb.execute(clickCommand);
    }

    // On down
    public void down() {

        long t = System.currentTimeMillis();
        down_times.add(t);

        if (up_times.size() > 0) {
            long last_up = up_times.get(this.up_times.size() - 1);
            long time_since_last_up = t - last_up;

            //if (time_since_last_up > 1000) {
            //    this.reset();
            //    return;
            //}
            /*
            if (time_since_last_up > 300) {
                morseString += " ";
            }
            */
        }
    }

    // On up
    public void up() {

        // On reset, down_times will be empty
        // and calculations will fail if there are a different number of downs and ups
        if (down_times.size() != up_times.size()+1) {
            System.out.println("OUT OF SYNC");
            return;
        }

        final long t = System.currentTimeMillis();
        up_times.add(t);

        long last_down = down_times.get(this.down_times.size() - 1);
        long down_time = t - last_down;

        if (down_time < 150)
            clickCommand += "0";
        else
            clickCommand += "1";


        if (timeout_thread != null) timeout_thread.interrupt();
        // timeout to check if input complete
        timeout_thread = new Thread(new Runnable() {
            public void run() {
                try {
                    long last_up_time = t;
                    Thread.sleep((long) 1000);
                    if (up_times.size() == 0 || down_times.size() == 0) return;
                    if(last_up_time == up_times.get(up_times.size() - 1))
                        reset();
                } catch (InterruptedException e) {
                    System.out.println("thread canceled");
                }
            }
        });
        timeout_thread.start();
    }

    public String getClickCommand() {
        return clickCommand;
    }

    public String convertMorse() {
        return "";
    }
}
