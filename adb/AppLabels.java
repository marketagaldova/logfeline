import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.content.Context;
import java.lang.reflect.*;


public class AppLabels {
	public static void main(String[] args) {
		Looper.prepareMainLooper();
        PackageManager pm = getSystemContext().getPackageManager();
        if (args.length > 0) try {
            ApplicationInfo info = pm.getApplicationInfo(args[0], PackageManager.MATCH_UNINSTALLED_PACKAGES);
            System.out.println(pm.getApplicationLabel(info));
        }
        catch (PackageManager.NameNotFoundException e) {}
        else {
            for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES))
                System.out.println(info.packageName + ":" + pm.getApplicationLabel(info));
        }
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
