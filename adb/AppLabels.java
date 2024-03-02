import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.content.Context;
import java.lang.reflect.*;
import java.util.Scanner;
import java.util.List;


public class AppLabels {
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        PackageManager pm = getSystemContext().getPackageManager();

        if (args.length == 0) {
            System.err.println("At least one of --list-all or --serve has to be provided");
            return;
        }

        if ("--list-all".equals(args[0])) {
            for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES))
                System.out.println(info.packageName + ":" + pm.getApplicationLabel(info));
            return;
        }

        if ("--serve".equals(args[0])) {
            serve(pm);
            return;
        }

        System.err.println("Unknown argument: " + args[0]);
    }

    private static final class PingThread extends Thread {
        private Object lock;

        public PingThread(Object lock) {
            super();
            this.lock = lock;
        }

        @Override public void run() {
            int counter = 0;
            do {
                try { Thread.sleep(3000); }
                catch (InterruptedException e) { break; }
                synchronized (lock) { System.out.println("ping:" + counter); }
                counter++;
            } while (!isInterrupted());
        }
    }

    private static void serve(PackageManager pm) {
        Object lock = new Object();

        Thread pingThread = new PingThread(lock);
        pingThread.start();

        try {
            Scanner stdin = new Scanner(System.in);
            while (stdin.hasNextLine()) {
                String command = stdin.nextLine();

                if ("list-all".equals(command)) {
                    List<ApplicationInfo> installedApplications = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    synchronized (lock) {
                        System.out.println("listing");
                        for (ApplicationInfo info : installedApplications)
                            System.out.println(info.packageName + ":" + pm.getApplicationLabel(info));
                        System.out.println();
                    }
                    continue;
                }

                if (command.startsWith("find:")) {
                    String packageName = command.substring(5);
                    try {
                        ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                        synchronized (lock) { System.out.println("package:" + packageName + ":" + pm.getApplicationLabel(info)); }
                    }
                    catch (PackageManager.NameNotFoundException e) {
                        synchronized (lock) { System.out.println("error:not-found:" + packageName); }
                    }
                    continue;
                }

                if ("exit".equals(command)) break;

                synchronized (lock) { System.out.println("error:unknown-command:" + command); }
            }
        }
        finally { pingThread.interrupt(); }
    }


    // We use reflection for ActivityThread as it is not in the sdk and it's already painful enough to get this to work...
    private static Context getSystemContext() {
        try {
            Class ActivityThread = Class.forName("android.app.ActivityThread");
            Method systemMain = null;
            Method getSystemContext = null;
            for (Method method : ActivityThread.getMethods()) {
                if (method.getName().equals("systemMain")) systemMain = method;
                if (method.getName().equals("getSystemContext")) getSystemContext = method;
            }
            return (Context) getSystemContext.invoke(systemMain.invoke(null));
        }
        catch (ClassNotFoundException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(null, e);
        }
    }
}
