import android.os.IBinder;
import android.os.Parcel;
public final class LaunchProbe {
 public static void main(String[] args) throws Exception {
  if (args.length != 1) throw new IllegalArgumentException("exactly one prepared command");
  Class<?> sm=Class.forName("android.os.ServiceManager");
  IBinder binder=(IBinder)sm.getDeclaredMethod("getService",String.class).invoke(null,"PServerBinder");
  if(binder==null) throw new IllegalStateException("PServer missing");
  Parcel data=Parcel.obtain(),reply=Parcel.obtain();
  try {data.writeStringArray(new String[]{args[0],"0"});
   System.out.println("PServer transaction accepted="+binder.transact(0,data,reply,0));
  } finally {data.recycle();reply.recycle();}
 }
}
