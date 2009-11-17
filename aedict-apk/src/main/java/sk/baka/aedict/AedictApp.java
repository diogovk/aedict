/**
 *     Aedict - an EDICT browser for Android
 Copyright (C) 2009 Martin Vysny
 
 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.
 
 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package sk.baka.aedict;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Formatter;

import sk.baka.aedict.kanji.RomanizationEnum;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * The main application class.
 * 
 * @author Martin Vysny
 */
public class AedictApp extends Application {

	@Override
	public void onCreate() {
		super.onCreate();
		if (instance != null) {
			throw new IllegalStateException("Not a singleton");
		}
		instance = this;
		apply(loadConfig());
	}

	private static AedictApp instance;

	/**
	 * Returns the application instance.
	 * 
	 * @return the instance.
	 */
	public static AedictApp getApp() {
		if (instance == null) {
			throw new IllegalStateException("Not yet initialized");
		}
		return instance;
	}

	/**
	 * Formats given string using {@link Formatter} and returns it.
	 * 
	 * @param resId
	 *            the string id
	 * @param args
	 *            the parameters.
	 * @return a formatted string.
	 */
	public static String format(final int resId, Object... args) {
		final String formatStr = getApp().getString(resId);
		return new Formatter().format(formatStr, args).toString();
	}

	/**
	 * The Ambient version.
	 */
	private static String version;

	/**
	 * Returns the Ambient version.
	 * 
	 * @return the version string or "unknown" if the version is not available.
	 */
	public static String getVersion() {
		if (version != null) {
			return version;
		}
		final InputStream in = getApp().getClassLoader().getResourceAsStream("version");
		if (in != null) {
			try {
				version = new String(MiscUtils.readFully(in), "UTF-8");
			} catch (Exception ex) {
				Log.e(AedictApp.class.getSimpleName(), "Failed to get version", ex);
				version = "unknown";
			} finally {
				MiscUtils.closeQuietly(in);
			}
		}
		return version;
	}

	/**
	 * The configuration.
	 * 
	 * @author Martin Vysny
	 */
	public static class Config {
		/**
		 * Creates new configuration object.
		 * 
		 * @param loadDefaults
		 *            if true then the default values are set to the fields.
		 */
		public Config(final boolean loadDefaults) {
			if (loadDefaults) {
				romanization = RomanizationEnum.Hepburn;
				isAlwaysAvailable = false;
			}
		}

		/**
		 * Which romanization system to use. Defaults to Hepburn.
		 */
		public RomanizationEnum romanization;
		/**
		 * If true then a notification icon is registered.
		 */
		public Boolean isAlwaysAvailable;
	}

	/**
	 * Loads the configuration from a shared preferences.
	 * 
	 * @return the configuration.
	 */
	public static Config loadConfig() {
		final SharedPreferences prefs = instance.getSharedPreferences("cfg", Context.MODE_PRIVATE);
		final Config result = new Config(true);
		for (final Field f : Config.class.getFields()) {
			final Class<?> type = f.getType();
			final String key = f.getName();
			final Object value;
			if (type == boolean.class || type == Boolean.class) {
				value = prefs.getBoolean(key, false);
			} else if (type == String.class) {
				value = prefs.getString(key, null);
			} else if (Enum.class.isAssignableFrom(type)) {
				final String name = prefs.getString(key, null);
				value = name == null ? null : Enum.valueOf((Class<Enum>) type, name);
			} else if (type == Integer.class || type == int.class) {
				value = prefs.getInt(key, 0);
			} else {
				throw new IllegalArgumentException("Unsupported class: " + type);
			}
			if (value == null) {
				// leave the default value
				continue;
			}
			try {
				f.set(result, value);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		return result;
	}

	/**
	 * Stores new configuration.
	 * 
	 * @param cfg
	 *            the configuration, must not be null.
	 */
	public static void saveConfig(final Config cfg) {
		final SharedPreferences.Editor prefs = instance.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit();
		for (final Field f : Config.class.getFields()) {
			final Class<?> type = f.getType();
			final String key = f.getName();
			final Object value;
			try {
				value = f.get(cfg);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			if (value == null) {
				// do not alter this value
				continue;
			}
			if (type == boolean.class || type == Boolean.class) {
				prefs.putBoolean(key, (Boolean) value);
			} else if (type == String.class) {
				prefs.putString(key, (String) value);
			} else if (Enum.class.isAssignableFrom(type)) {
				prefs.putString(key, ((Enum<?>) value).name());
			} else if (type == Integer.class || type == int.class) {
				prefs.putInt(key, (Integer) value);
			} else {
				throw new IllegalArgumentException("Unsupported class: " + type);
			}
		}
		prefs.commit();
		apply(loadConfig());
	}

	private static final int NOTIFICATION_ID = 1;

	/**
	 * Applies values from given configuration file, e.g. removes or adds the
	 * notification icon etc.
	 * 
	 * @param cfg
	 *            non-null configuration
	 */
	private static void apply(final Config cfg) {
		final NotificationManager nm = (NotificationManager) instance.getSystemService(Context.NOTIFICATION_SERVICE);
		if (!cfg.isAlwaysAvailable) {
			nm.cancel(NOTIFICATION_ID);
		} else {
			final Notification notification = new Notification(R.drawable.notification, null, 0);
			final PendingIntent contentIntent = PendingIntent.getActivity(instance, 0, new Intent(instance, MainActivity.class), 0);
			notification.setLatestEventInfo(instance, "Aedict", "A japanese dictionary", contentIntent);
			notification.flags = Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
			nm.notify(NOTIFICATION_ID, notification);
		}
	}

	/**
	 * Returns a safe wrapper for given interface. Any exceptions thrown from
	 * interface methods are catched, logged and shown in a dialog.
	 * 
	 * @param <T>
	 *            the interface type
	 * @param intf
	 *            the interface class
	 * @param instance
	 *            the instance
	 * @return a protected proxy
	 */
	public static <T> T safe(final Class<T> intf, final T instance) {
		if (!intf.isInterface()) {
			throw new IllegalArgumentException("Must be an interface: " + intf);
		}
		return intf.cast(Proxy.newProxyInstance(AedictApp.instance.getClassLoader(), new Class<?>[] { intf }, new Safe(instance)));
	}

	/**
	 * Returns a safe wrapper for given interface. Any exceptions thrown from
	 * interface methods are catched, logged and shown in a dialog.
	 * 
	 * @param <T>
	 *            the interface type
	 * @param instance
	 *            the instance. The object must implement exactly one interface.
	 * @return a protected proxy
	 */
	@SuppressWarnings("unchecked")
	public static <T> T safe(final T instance) {
		final Class<?>[] intfs = instance.getClass().getInterfaces();
		if (intfs.length == 0) {
			throw new IllegalArgumentException("Given class " + instance.getClass() + " does not implement any interfaces");
		}
		if (intfs.length > 1) {
			throw new IllegalArgumentException("Given class " + instance.getClass() + " implements multiple interfaces");
		}
		final Class<Object> intf = (Class) intfs[0];
		final Object safe = safe(intf, instance);
		// this is a bit ugly. The safe object will not of type T anymore, but
		// this cast will succeed (because it is silently ignored by Java).
		return (T) safe;
	}

	private static class Safe implements InvocationHandler {

		private final Object instance;

		public Safe(Object instance) {
			this.instance = instance;
		}

		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			try {
				return method.invoke(instance, args);
			} catch (InvocationTargetException ex) {
				final Throwable cause = unwrap(ex);
				Log.e(instance.getClass().getSimpleName(), "Exception thrown while invoking " + method, cause);
				new AndroidUtils(AedictApp.getApp()).showErrorDialog("An application problem occured: " + cause.toString());
				if (method.getReturnType() == Boolean.class || method.getReturnType() == boolean.class) {
					return false;
				}
				return null;
			}
		}
	}

	/**
	 * Unwrap all {@link RuntimeException}s and
	 * {@link InvocationTargetException}s.
	 * 
	 * @param t
	 *            the exception type
	 * @return unwrapped throwable.
	 */
	public static Throwable unwrap(final Throwable t) {
		Throwable current = t;
		while (current instanceof RuntimeException || current instanceof InvocationTargetException) {
			if (current.getCause() == null) {
				return current;
			}
			current = current.getCause();
		}
		return current;
	}
}
