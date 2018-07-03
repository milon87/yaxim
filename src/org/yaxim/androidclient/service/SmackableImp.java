package org.yaxim.androidclient.service;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import de.duenndns.ssl.MemorizingTrustManager;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Presence.Mode;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smack.parsing.ParsingExceptionCallback;
import org.jivesoftware.smack.parsing.UnparsablePacket;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.util.DNSUtil;
import org.jivesoftware.smack.util.dns.DNSJavaResolver;
import org.jivesoftware.smackx.FormField;
import org.jivesoftware.smackx.PrivateDataManager;
import org.jivesoftware.smackx.bookmark.BookmarkManager;
import org.jivesoftware.smackx.bookmark.BookmarkedConference;
import org.jivesoftware.smackx.entitycaps.EntityCapsManager;
import org.jivesoftware.smackx.entitycaps.cache.SimpleDirectoryPersistentCache;
import org.jivesoftware.smackx.GroupChatInvitation;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.jivesoftware.smackx.muc.DiscussionHistory;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jivesoftware.smackx.carbons.Carbon;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.entitycaps.provider.CapsExtensionProvider;
import org.jivesoftware.smackx.forward.Forwarded;
import org.jivesoftware.smackx.packet.DataForm;
import org.jivesoftware.smackx.packet.DiscoverItems;
import org.jivesoftware.smackx.provider.DataFormProvider;
import org.jivesoftware.smackx.provider.DelayInfoProvider;
import org.jivesoftware.smackx.provider.DiscoverInfoProvider;
import org.jivesoftware.smackx.provider.DiscoverItemsProvider;
import org.jivesoftware.smackx.provider.MUCAdminProvider;
import org.jivesoftware.smackx.provider.MUCOwnerProvider;
import org.jivesoftware.smackx.provider.MUCUserProvider;
import org.jivesoftware.smackx.packet.DelayInformation;
import org.jivesoftware.smackx.packet.DelayInfo;
import org.jivesoftware.smackx.packet.DiscoverInfo;
import org.jivesoftware.smackx.packet.MUCUser;
import org.jivesoftware.smackx.packet.Version;
import org.jivesoftware.smackx.ping.PingManager;
import org.jivesoftware.smackx.ping.packet.*;
import org.jivesoftware.smackx.ping.provider.PingProvider;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener;
import org.yaxim.androidclient.YaximApplication;
import org.yaxim.androidclient.data.ChatHelper;
import org.yaxim.androidclient.data.ChatProvider;
import org.yaxim.androidclient.data.ChatRoomHelper;
import org.yaxim.androidclient.data.RosterProvider;
import org.yaxim.androidclient.data.YaximConfiguration;
import org.yaxim.androidclient.data.ChatProvider.ChatConstants;
import org.yaxim.androidclient.data.RosterProvider.RosterConstants;
import org.yaxim.androidclient.exceptions.YaximXMPPException;
import org.yaxim.androidclient.packet.Oob;
import org.yaxim.androidclient.packet.PreAuth;
import org.yaxim.androidclient.packet.Replace;
import org.yaxim.androidclient.packet.httpupload.Slot;
import org.yaxim.androidclient.util.ConnectionState;
import org.yaxim.androidclient.util.LogConstants;
import org.yaxim.androidclient.util.StatusMode;
import org.yaxim.androidclient.R;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

public class SmackableImp implements Smackable {
	final static private String TAG = "yaxim.SmackableImp";

	final static private int PACKET_TIMEOUT = 30000;

	final static private String[] SEND_OFFLINE_PROJECTION = new String[] {
			ChatConstants._ID, ChatConstants.JID,
			ChatConstants.MESSAGE, ChatConstants.DATE, ChatConstants.PACKET_ID };
	final static private String SEND_OFFLINE_SELECTION =
			ChatConstants.DIRECTION + " = " + ChatConstants.OUTGOING + " AND " +
			ChatConstants.DELIVERY_STATUS + " = " + ChatConstants.DS_NEW;

	static final DiscoverInfo.Identity YAXIM_IDENTITY = new DiscoverInfo.Identity("client",
					YaximApplication.XMPP_IDENTITY_NAME,
					YaximApplication.XMPP_IDENTITY_TYPE);

	static File capsCacheDir = null; ///< this is used to cache if we already initialized EntityCapsCache

	static {
		registerSmackProviders();
		DNSUtil.setDNSResolver(DNSJavaResolver.getInstance());

		// initialize smack defaults before any connections are created
		SmackConfiguration.setPacketReplyTimeout(PACKET_TIMEOUT);
		SmackConfiguration.setDefaultPingInterval(0);
	}

	static void registerSmackProviders() {
		ProviderManager pm = ProviderManager.getInstance();
		// add IQ handling
		pm.addIQProvider("query","http://jabber.org/protocol/disco#info", new DiscoverInfoProvider());
		pm.addIQProvider("query","http://jabber.org/protocol/disco#items", new DiscoverItemsProvider());
		// add delayed delivery notifications
		pm.addExtensionProvider("delay","urn:xmpp:delay", new DelayInfoProvider());
		pm.addExtensionProvider("x","jabber:x:delay", new DelayInfoProvider());
		// add XEP-0092 Software Version
		pm.addIQProvider("query", Version.NAMESPACE, new Version.Provider());

		// data forms
		pm.addExtensionProvider("x","jabber:x:data", new DataFormProvider());

		// add carbons and forwarding
		pm.addExtensionProvider("forwarded", Forwarded.NAMESPACE, new Forwarded.Provider());
		pm.addExtensionProvider("sent", Carbon.NAMESPACE, new Carbon.Provider());
		pm.addExtensionProvider("received", Carbon.NAMESPACE, new Carbon.Provider());
		// add delivery receipts
		pm.addExtensionProvider(DeliveryReceipt.ELEMENT, DeliveryReceipt.NAMESPACE, new DeliveryReceipt.Provider());
		pm.addExtensionProvider(DeliveryReceiptRequest.ELEMENT, DeliveryReceipt.NAMESPACE, new DeliveryReceiptRequest.Provider());
		// add XMPP Ping (XEP-0199)
		pm.addIQProvider("ping","urn:xmpp:ping", new PingProvider());

		ServiceDiscoveryManager.setDefaultIdentity(YAXIM_IDENTITY);
		
		// XEP-0115 Entity Capabilities
		pm.addExtensionProvider("c", "http://jabber.org/protocol/caps", new CapsExtensionProvider());

		// XEP-0308 Last Message Correction
		pm.addExtensionProvider("replace", Replace.NAMESPACE, new Replace.Provider());

		// XEP-XXXX Pre-Authenticated Roster Subscription
		pm.addExtensionProvider("preauth", PreAuth.NAMESPACE, new PreAuth.Provider());

		//  MUC User
		pm.addExtensionProvider("x","http://jabber.org/protocol/muc#user", new MUCUserProvider());
		// MUC direct invitation
		pm.addExtensionProvider("x","jabber:x:conference", new GroupChatInvitation.Provider());
		//  MUC Admin
		pm.addIQProvider("query","http://jabber.org/protocol/muc#admin", new MUCAdminProvider());
		//  MUC Owner
		pm.addIQProvider("query","http://jabber.org/protocol/muc#owner", new MUCOwnerProvider());
		pm.addIQProvider("query","http://jabber.org/protocol/muc#owner", new MUCOwnerProvider());
		pm.addIQProvider("query","jabber:iq:private", new PrivateDataManager.PrivateDataIQProvider());

		// HTTP Upload and OOB
		pm.addIQProvider(Slot.NAME, Slot.XMLNS, new Slot.Provider());
		pm.addExtensionProvider(Oob.ELEMENT, Oob.NAMESPACE, new Oob.Provider());

		XmppStreamHandler.addExtensionProviders();
	}

	private final YaximConfiguration mConfig;
	private ConnectionConfiguration mXMPPConfig;
	private XmppStreamHandler.ExtXMPPConnection mXMPPConnection;
	private XmppStreamHandler mStreamHandler;
	private Thread mConnectingThread;
	private Object mConnectingThreadMutex = new Object();


	private ConnectionState mRequestedState = ConnectionState.OFFLINE;
	private ConnectionState mState = ConnectionState.OFFLINE;
	private String mLastError;
	private long mLastOnline = 0;	//< timestamp of last successful full login (XEP-0198 does not count)
	private long mLastOffline = 0;	//< timestamp of the end of last successful login

	private XMPPServiceCallback mServiceCallBack;
	private Roster mRoster;
	private RosterListener mRosterListener;
	private PacketListener mPacketListener;
	private PacketListener mPresenceListener;
	private ConnectionListener mConnectionListener;

	private final ContentResolver mContentResolver;

	private AlarmManager mAlarmManager;
	private PacketListener mPongListener;
	private String mPingID;
	private long mPingTimestamp;

	private PendingIntent mPingAlarmPendIntent;
	private PendingIntent mPongTimeoutAlarmPendIntent;
	private static final String PING_ALARM = "org.yaxim.androidclient.PING_ALARM";
	private static final String PONG_TIMEOUT_ALARM = "org.yaxim.androidclient.PONG_TIMEOUT_ALARM";
	private Intent mPingAlarmIntent = new Intent(PING_ALARM);
	private Intent mPongTimeoutAlarmIntent = new Intent(PONG_TIMEOUT_ALARM);
	private Service mService;

	private PongTimeoutAlarmReceiver mPongTimeoutAlarmReceiver = new PongTimeoutAlarmReceiver();
	private BroadcastReceiver mPingAlarmReceiver = new PingAlarmReceiver();
	
	private final HashSet<String> mucJIDs = new HashSet<String>();	//< all configured MUCs, joined or not
	private Map<String, MUCController> multiUserChats;
	private long mucLastPing = 0;
	private Map<String, Long> mucLastPong = new HashMap<String, Long>();	//< per-MUC timestamp of last incoming ping result
	private Map<String, Presence> subscriptionRequests = new HashMap<String, Presence>();


	public SmackableImp(YaximConfiguration config,
			ContentResolver contentResolver,
			Service service) {
		this.mConfig = config;
		this.mContentResolver = contentResolver;
		this.mService = service;
		this.mAlarmManager = (AlarmManager)mService.getSystemService(Context.ALARM_SERVICE);

		mLastOnline = mLastOffline = System.currentTimeMillis();
		mPingAlarmPendIntent = PendingIntent.getBroadcast(mService.getApplicationContext(), 0, mPingAlarmIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
		mPongTimeoutAlarmPendIntent = PendingIntent.getBroadcast(mService.getApplicationContext(), 0, mPongTimeoutAlarmIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
	}
		
	// this code runs a DNS resolver, might be blocking
	private synchronized void initXMPPConnection() {
		// allow custom server / custom port to override SRV record
		if (mConfig.customServer.length() > 0)
			mXMPPConfig = new ConnectionConfiguration(mConfig.customServer,
					mConfig.port, mConfig.server);
		else
			mXMPPConfig = new ConnectionConfiguration(mConfig.server); // use SRV
		mXMPPConfig.setReconnectionAllowed(false);
		mXMPPConfig.setSendPresence(false);
		mXMPPConfig.setCompressionEnabled(false); // disable for now
		mXMPPConfig.setDebuggerEnabled(mConfig.smackdebug);
		if (mConfig.require_ssl)
			this.mXMPPConfig.setSecurityMode(ConnectionConfiguration.SecurityMode.required);

		// register MemorizingTrustManager for XMPP and HTTPS
		try {
			SSLContext sc = SSLContext.getInstance("TLS");
			MemorizingTrustManager mtm = YaximApplication.getApp(mService).mMTM;
			sc.init(null, new X509TrustManager[] { mtm },
					new java.security.SecureRandom());
			// XMPP
			this.mXMPPConfig.setCustomSSLContext(sc);
			this.mXMPPConfig.setHostnameVerifier(mtm.wrapHostnameVerifier(
						new org.apache.http.conn.ssl.StrictHostnameVerifier()));
			// HTTPS (for HTTP Upload)
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
			HttpsURLConnection.setDefaultHostnameVerifier(mtm.wrapHostnameVerifier(
						HttpsURLConnection.getDefaultHostnameVerifier()));
		} catch (java.security.GeneralSecurityException e) {
			debugLog("initialize MemorizingTrustManager: " + e);
		}

		this.mXMPPConnection = new XmppStreamHandler.ExtXMPPConnection(mXMPPConfig);
		mXMPPConnection.setParsingExceptionCallback(new ParsingExceptionCallback() {
			@Override
			public void handleUnparsablePacket(UnparsablePacket up) throws Exception {
				Exception e = up.getParsingException();
				// work around Smack throwing up on mod_mam_archive bug
				if (e.getMessage().equals("variable cannot be null")) {
					debugLog("Ignoring invalid disco#info caused by https://prosody.im/issues/issue/870");
					e.printStackTrace();
					return;
				}
				throw e;
			}
		});

		this.mStreamHandler = new XmppStreamHandler(mXMPPConnection, mConfig.smackdebug);
		mStreamHandler.addAckReceivedListener(new XmppStreamHandler.AckReceivedListener() {
			public void ackReceived(long handled, long total) {
				gotServerPong("" + handled);
			}
		});
		mConfig.reconnect_required = false;

		multiUserChats = new HashMap<String, MUCController>();
		initServiceDiscovery();
	}

	// blocking, run from a thread!
	public boolean doConnect(boolean create_account) throws YaximXMPPException {
		mRequestedState = ConnectionState.ONLINE;
		updateConnectionState(ConnectionState.CONNECTING);
		if (mXMPPConnection == null || mConfig.reconnect_required)
			initXMPPConnection();
		boolean fresh_session = tryToConnect(create_account);
		if (!mXMPPConnection.isAuthenticated())
			throw new YaximXMPPException("SMACK connected, but authentication failed");
		updateConnectionState(ConnectionState.LOADING);
		registerMessageListener();
		registerPresenceListener();
		registerPongListener();
		if (fresh_session) {
			cleanupMUCs(true);
			setStatusFromConfig();
			discoverServicesAsync();
		}
		syncDbRooms();
		sendOfflineMessages(null);
		sendUserWatching();
		// we need to "ping" the service to let it know we are actually
		// connected, even when no roster entries will come in
		updateConnectionState(ConnectionState.ONLINE);
		return true;
	}

	// BLOCKING, call on a new Thread!
	private void updateConnectingThread(Thread new_thread) {
		synchronized(mConnectingThreadMutex) {
			if (mConnectingThread == null) {
				mConnectingThread = new_thread;
			} else try {
				Log.d(TAG, "updateConnectingThread: old thread is still running, killing it.");
				mConnectingThread.interrupt();
				mConnectingThread.join(50);
			} catch (InterruptedException e) {
				Log.d(TAG, "updateConnectingThread: failed to join(): " + e);
			} finally {
				mConnectingThread = new_thread;
			}
		}
	}
	private void finishConnectingThread() {
		synchronized(mConnectingThreadMutex) {
			mConnectingThread = null;
		}
	}

	/** Non-blocking, synchronized function to connect/disconnect XMPP.
	 * This code is called from outside and returns immediately. The actual work
	 * is done on a background thread, and notified via callback.
	 * @param new_state The state to transition into. Possible values:
	 * 	OFFLINE to properly close the connection
	 * 	ONLINE to connect
	 * 	DISCONNECTED when network goes down
	 * @param create_account When going online, try to register an account.
	 */
	@Override
	public synchronized void requestConnectionState(ConnectionState new_state, final boolean create_account) {
		Log.d(TAG, "requestConnState: " + mState + " -> " + new_state + (create_account ? " create_account!" : ""));
		mRequestedState = new_state;
		if (new_state == mState)
			return;
		switch (new_state) {
		case ONLINE:
			switch (mState) {
			case RECONNECT_DELAYED:
				// TODO: cancel timer
			case RECONNECT_NETWORK:
			case DISCONNECTED:
			case OFFLINE:
				// update state before starting thread to prevent race conditions
				updateConnectionState(ConnectionState.CONNECTING);

				// register ping (connection) timeout handler: 2*PACKET_TIMEOUT(30s) + 3s
				registerPongTimeout(2*PACKET_TIMEOUT + 3000, "connection");

				new Thread() {
					@Override
					public void run() {
						updateConnectingThread(this);
						try {
							doConnect(create_account);
						} catch (IllegalArgumentException e) {
							// this might happen when DNS resolution in ConnectionConfiguration fails
							onDisconnected(e);
						} catch (IllegalStateException e) {//TODO: work around old smack
							onDisconnected(e);
						} catch (YaximXMPPException e) {
							onDisconnected(e);
						} finally {
							mAlarmManager.cancel(mPongTimeoutAlarmPendIntent);
							finishConnectingThread();
						}
					}
				}.start();
				break;
			case CONNECTING:
			case LOADING:
			case DISCONNECTING:
				// ignore all other cases
				break;
			}
			break;
		case DISCONNECTED:
			// spawn thread to do disconnect
			if (mState == ConnectionState.ONLINE) {
				// update state before starting thread to prevent race conditions
				updateConnectionState(ConnectionState.DISCONNECTING);

				// register ping (connection) timeout handler: PACKET_TIMEOUT(30s)
				registerPongTimeout(PACKET_TIMEOUT, "forced disconnect");

				new Thread() {
					public void run() {
						updateConnectingThread(this);
						mStreamHandler.quickShutdown();
						onDisconnected("forced disconnect completed");
						finishConnectingThread();
						//updateConnectionState(ConnectionState.OFFLINE);
					}
				}.start();
			}
			break;
		case OFFLINE:
			switch (mState) {
			case CONNECTING:
			case LOADING:
			case ONLINE:
				// update state before starting thread to prevent race conditions
				updateConnectionState(ConnectionState.DISCONNECTING);

				// register ping (connection) timeout handler: PACKET_TIMEOUT(30s)
				registerPongTimeout(PACKET_TIMEOUT, "manual disconnect");

				// spawn thread to do disconnect
				new Thread() {
					public void run() {
						updateConnectingThread(this);
						mXMPPConnection.shutdown();
						mStreamHandler.close();
						mAlarmManager.cancel(mPongTimeoutAlarmPendIntent);
						// we should reset XMPPConnection the next time
						mConfig.reconnect_required = true;
						finishConnectingThread();
						// reconnect if it was requested in the meantime
						if (mRequestedState == ConnectionState.ONLINE)
							requestConnectionState(ConnectionState.ONLINE);
					}
				}.start();
				break;
			case DISCONNECTING:
				break;
			case DISCONNECTED:
			case RECONNECT_DELAYED:
				// TODO: clear timer
			case RECONNECT_NETWORK:
				updateConnectionState(ConnectionState.OFFLINE);
			}
			break;
		case RECONNECT_NETWORK:
		case RECONNECT_DELAYED:
			switch (mState) {
			case DISCONNECTED:
			case RECONNECT_NETWORK:
			case RECONNECT_DELAYED:
				updateConnectionState(new_state);
				break;
			default:
				throw new IllegalArgumentException("Can not go from " + mState + " to " + new_state);
			}
		}
	}
	@Override
	public void requestConnectionState(ConnectionState new_state) {
		requestConnectionState(new_state, false);
	}

	@Override
	public ConnectionState getConnectionState() {
		return mState;
	}

	@Override
	public long getConnectionStateTimestamp() {
		return (mState == ConnectionState.ONLINE) ? mLastOnline : mLastOffline;
	}

	// called at the end of a state transition
	private synchronized void updateConnectionState(ConnectionState new_state) {
		if (new_state == ConnectionState.ONLINE || new_state == ConnectionState.LOADING)
			mLastError = null;
		Log.d(TAG, "updateConnectionState: " + mState + " -> " + new_state + " (" + mLastError + ")");
		if (new_state == mState)
			return;
		if (mState == ConnectionState.ONLINE)
			mLastOffline = System.currentTimeMillis();
		mState = new_state;
		if (mServiceCallBack != null)
			mServiceCallBack.connectionStateChanged();
	}
	private void initServiceDiscovery() {
		// register connection features
		ServiceDiscoveryManager sdm = ServiceDiscoveryManager.getInstanceFor(mXMPPConnection);

		// init Entity Caps manager with storage in app's cache dir
		try {
			if (capsCacheDir == null) {
				capsCacheDir = new File(mService.getCacheDir(), "entity-caps-cache");
				capsCacheDir.mkdirs();
				EntityCapsManager.setPersistentCache(new SimpleDirectoryPersistentCache(capsCacheDir));
			}
		} catch (java.io.IOException e) {
			Log.e(TAG, "Could not init Entity Caps cache: " + e.getLocalizedMessage());
		}

		// reference PingManager, set ping flood protection to 10s
		PingManager.getInstanceFor(mXMPPConnection).disablePingFloodProtection();

		// manually add LMC feature (XEP-0308) because it lacks a Manager
		sdm.addFeature(Replace.NAMESPACE);

		// set Version for replies
		String app_name = mService.getString(R.string.app_name);
		String build_revision = mService.getString(R.string.build_revision);
		Version.Manager.getInstanceFor(mXMPPConnection).setVersion(
				new Version(app_name, build_revision, "Android"));

		// reference DeliveryReceiptManager, add listener
		DeliveryReceiptManager dm = DeliveryReceiptManager.getInstanceFor(mXMPPConnection);
		dm.addReceiptReceivedListener(new ReceiptReceivedListener() { // DOES NOT WORK IN CARBONS
			public void onReceiptReceived(String fromJid, String toJid, String receiptId) {
				Log.d(TAG, "got delivery receipt for " + receiptId);
				changeMessageDeliveryStatus(receiptId, ChatConstants.DS_ACKED);
			}});
	}

	public void addRosterItem(String user, String alias, String group, String token)
			throws YaximXMPPException {
		subscriptionRequests.remove(user);
		mConfig.whitelistInvitationJID(user);
		tryToAddRosterEntry(user, alias, group, token);
	}

	public void removeRosterItem(String user) throws YaximXMPPException {
		debugLog("removeRosterItem(" + user + ")");
		subscriptionRequests.remove(user);
		tryToRemoveRosterEntry(user);
	}

	public void renameRosterItem(String user, String newName)
			throws YaximXMPPException {
		RosterEntry rosterEntry = mRoster.getEntry(user);

		if (!(newName.length() > 0) || (rosterEntry == null)) {
			throw new YaximXMPPException("JabberID to rename is invalid!");
		}
		rosterEntry.setName(newName);
	}

	public void addRosterGroup(String group) {
		mRoster.createGroup(group);
	}

	public void renameRosterGroup(String group, String newGroup) {
		RosterGroup groupToRename = mRoster.getGroup(group);
		groupToRename.setName(newGroup);
	}

	public void moveRosterItemToGroup(String user, String group)
			throws YaximXMPPException {
		tryToMoveRosterEntryToGroup(user, group);
	}

	public void sendPresenceRequest(String user, String type) {
		// HACK: remove the fake roster entry added by handleIncomingSubscribe()
		if (user == null) {
			for (String[] jid_name : ChatHelper.getRosterContacts(mService, ChatHelper.ROSTER_FILTER_SUBSCRIPTIONS)) {
				sendPresenceRequest(jid_name[0], type);
			}
			return;
		}
		subscriptionRequests.remove(user);
		if ("unsubscribed".equals(type))
			deleteRosterEntryFromDB(user);
		Presence response = new Presence(Presence.Type.valueOf(type));
		response.setTo(user);
		mXMPPConnection.sendPacket(response);
	}
	
	@Override
	public String changePassword(String newPassword) {
		try {
			new AccountManager(mXMPPConnection).changePassword(newPassword);
			return "OK"; //HACK: hard coded string to differentiate from failure modes
		} catch (XMPPException e) {
			if (e.getXMPPError() != null)
				return e.getXMPPError().toString();
			else
				return e.getLocalizedMessage();
		}
	}

	private void onDisconnected(String reason) {
		unregisterPongListener();
		mLastError = reason;
		updateConnectionState(ConnectionState.DISCONNECTED);
	}
	private void onDisconnected(Throwable reason) {
		Log.e(TAG, "onDisconnected: " + reason);
		reason.printStackTrace();
		// iterate through to the deepest exception
		while (reason.getCause() != null && !(reason.getCause().getClass().getSimpleName().equals("GaiException")))
			reason = reason.getCause();
		onDisconnected(reason.getLocalizedMessage());
	}

	/** establishes an XMPP connection and performs login / account creation.
	 *
	 * @param create_account
	 * @return true if this is a new session, as opposed to a resumed one
	 * @throws YaximXMPPException
	 */
	private boolean tryToConnect(boolean create_account) throws YaximXMPPException {
		try {
			if (mXMPPConnection.isConnected()) {
				try {
					mStreamHandler.quickShutdown(); // blocking shutdown prior to re-connection
				} catch (Exception e) {
					debugLog("conn.shutdown() failed: " + e);
				}
			}
			registerRosterListener();
			boolean need_bind = !mStreamHandler.isResumePossible();

			if (mConnectionListener != null)
				mXMPPConnection.removeConnectionListener(mConnectionListener);
			mConnectionListener = new ConnectionListener() {
				public void connectionClosedOnError(Exception e) {
					// XXX: this is the only callback we get from errors, so
					// we need to check for non-resumability and work around
					// here:
					if (!mStreamHandler.isResumePossible()) {
						for (MUCController muc : multiUserChats.values())
							muc.cleanup();
						multiUserChats.clear();
						mucLastPong.clear();
						mucLastPing = 0;
					}
					onDisconnected(e);
				}
				public void connectionClosed() {
					// TODO: fix reconnect when we got kicked by the server or SM failed!
					//onDisconnected(null);
					for (MUCController muc : multiUserChats.values())
						muc.cleanup();
					multiUserChats.clear();
					mucLastPong.clear();
					mucLastPing = 0;
					updateConnectionState(ConnectionState.OFFLINE);
				}
				public void reconnectingIn(int seconds) { }
				public void reconnectionFailed(Exception e) { }
				public void reconnectionSuccessful() { }
			};
			mXMPPConnection.addConnectionListener(mConnectionListener);

			mXMPPConnection.connect(need_bind);
			// SMACK auto-logins if we were authenticated before
			if (!mXMPPConnection.isAuthenticated()) {
				if (create_account) {
					Log.d(TAG, "creating new server account...");
					AccountManager am = new AccountManager(mXMPPConnection);
					am.createAccount(mConfig.userName, mConfig.password);
				}
				mXMPPConnection.login(mConfig.userName, mConfig.password,
						mConfig.ressource);
			}
			Log.d(TAG, "SM: can resume = " + mStreamHandler.isResumePossible() + " needbind=" + need_bind);
			if (need_bind) {
				mStreamHandler.notifyInitialLogin();
				mLastOnline = System.currentTimeMillis();
			}
			return need_bind;
		} catch (Exception e) {
			// actually we just care for IllegalState or NullPointer or XMPPEx.
			throw new YaximXMPPException("tryToConnect failed", e);
		}
	}

	private void tryToMoveRosterEntryToGroup(String userName, String groupName)
			throws YaximXMPPException {

		RosterGroup rosterGroup = getRosterGroup(groupName);
		RosterEntry rosterEntry = mRoster.getEntry(userName);

		removeRosterEntryFromGroups(rosterEntry);

		if (groupName.length() == 0)
			return;
		else {
			try {
				rosterGroup.addEntry(rosterEntry);
			} catch (XMPPException e) {
				throw new YaximXMPPException("tryToMoveRosterEntryToGroup", e);
			}
		}
	}

	private RosterGroup getRosterGroup(String groupName) {
		RosterGroup rosterGroup = mRoster.getGroup(groupName);

		// create group if unknown
		if ((groupName.length() > 0) && rosterGroup == null) {
			rosterGroup = mRoster.createGroup(groupName);
		}
		return rosterGroup;

	}

	private void removeRosterEntryFromGroups(RosterEntry rosterEntry)
			throws YaximXMPPException {
		Collection<RosterGroup> oldGroups = rosterEntry.getGroups();

		for (RosterGroup group : oldGroups) {
			tryToRemoveUserFromGroup(group, rosterEntry);
		}
	}

	private void tryToRemoveUserFromGroup(RosterGroup group,
			RosterEntry rosterEntry) throws YaximXMPPException {
		try {
			group.removeEntry(rosterEntry);
		} catch (XMPPException e) {
			throw new YaximXMPPException("tryToRemoveUserFromGroup", e);
		}
	}

	private void tryToRemoveRosterEntry(String user) throws YaximXMPPException {
		try {
			RosterEntry rosterEntry = mRoster.getEntry(user);

			if (rosterEntry != null) {
				// first, unsubscribe the user
				Presence unsub = new Presence(Presence.Type.unsubscribed);
				unsub.setTo(rosterEntry.getUser());
				mXMPPConnection.sendPacket(unsub);
				// then, remove from roster
				mRoster.removeEntry(rosterEntry);
			}
		} catch (XMPPException e) {
			throw new YaximXMPPException("tryToRemoveRosterEntry", e);
		}
	}

	private void tryToAddRosterEntry(String user, String alias, String group, String token)
			throws YaximXMPPException {
		try {
			// send a presence subscription request with token (must be before roster action!)
			if (token != null && token.length() > 0) {
				Presence preauth = new Presence(Presence.Type.subscribe);
				preauth.setTo(user);
				preauth.addExtension(new PreAuth(token));
				mXMPPConnection.sendPacket(preauth);
			}
			// add to roster, triggers another sub request by Smack (sigh)
			mRoster.createEntry(user, alias, new String[] { group });
			// send a pre-approval
			Presence pre_approval = new Presence(Presence.Type.subscribed);
			pre_approval.setTo(user);
			mXMPPConnection.sendPacket(pre_approval);
			mConfig.whitelistInvitationJID(user);
		} catch (XMPPException e) {
			throw new YaximXMPPException("tryToAddRosterEntry", e);
		}
	}

	private void removeOldRosterEntries() {
		Log.d(TAG, "removeOldRosterEntries()");
		Collection<RosterEntry> rosterEntries = mRoster.getEntries();
		StringBuilder exclusion = new StringBuilder(RosterConstants.JID + " NOT IN (");
		boolean first = true;
		
		for (RosterEntry rosterEntry : rosterEntries) {
			if (first)
				first = false;
			else
				exclusion.append(",");
			exclusion.append("'").append(rosterEntry.getUser()).append("'");
		}
		
		exclusion.append(") AND "+RosterConstants.GROUP+" NOT IN ('" + RosterProvider.RosterConstants.MUCS + "');");
		int count = mContentResolver.delete(RosterProvider.CONTENT_URI, exclusion.toString(), null);
		Log.d(TAG, "deleted " + count + " old roster entries");
	}

	// HACK: add an incoming subscription request as a fake roster entry
	private void handleIncomingSubscribe(Presence request) {
		// perform Pre-Authenticated Roster Subscription, fallback to manual
		try {
			String jid = request.getFrom();
			PreAuth preauth = (PreAuth)request.getExtension(PreAuth.ELEMENT, PreAuth.NAMESPACE);
			String jid_or_token = jid;
			if (preauth != null) {
				jid_or_token = preauth.getToken();
				Log.d(TAG, "PARS: found token " + jid_or_token);
			}
			if (mConfig.redeemInvitationCode(jid_or_token)) {
				Log.d(TAG, "PARS: approving request from " + jid);
				if (mRoster.getEntry(request.getFrom()) != null) {
					// already in roster, only send approval
					Presence response = new Presence(Presence.Type.subscribed);
					response.setTo(jid);
					mXMPPConnection.sendPacket(response);
				} else {
					tryToAddRosterEntry(jid, null, "", null);
				}
				return;
			}
		} catch (YaximXMPPException e) {
			Log.d(TAG, "PARS: failed to send response: " + e);
		}

		subscriptionRequests.put(request.getFrom(), request);

		final ContentValues values = new ContentValues();

		values.put(RosterConstants.JID, request.getFrom());
		values.put(RosterConstants.STATUS_MODE, getStatusInt(request));
		values.put(RosterConstants.STATUS_MESSAGE, request.getStatus());
		if (!mRoster.contains(request.getFrom())) {
			// reset alias and group for new entries
			values.put(RosterConstants.ALIAS, request.getFrom());
			values.put(RosterConstants.GROUP, "");
		};
		upsertRoster(values, request.getFrom());
	}

	public void setStatusFromConfig() {
		// TODO: only call this when carbons changed, not on every presence change
		CarbonManager.getInstanceFor(mXMPPConnection).sendCarbonsEnabled(mConfig.messageCarbons);

		Presence presence = new Presence(Presence.Type.available);
		Mode mode = Mode.valueOf(mConfig.getPresenceMode().toString());
		presence.setMode(mode);
		presence.setStatus(mConfig.statusMessage);
		presence.setPriority(mConfig.priority);
		mXMPPConnection.sendPacket(presence);
		mConfig.presence_required = false;
	}

	public void sendOfflineMessages(String toMUCjid) {
		boolean is_muc = (toMUCjid != null);
		String selection = SEND_OFFLINE_SELECTION;
		String[] selection_args = null;
		if (is_muc) {
			selection = selection + " AND jid = ?";
			selection_args = new String[] { toMUCjid };
		}
		Cursor cursor = mContentResolver.query(ChatProvider.CONTENT_URI,
				SEND_OFFLINE_PROJECTION, selection,
				selection_args, null);
		final int      _ID_COL = cursor.getColumnIndexOrThrow(ChatConstants._ID);
		final int      JID_COL = cursor.getColumnIndexOrThrow(ChatConstants.JID);
		final int      MSG_COL = cursor.getColumnIndexOrThrow(ChatConstants.MESSAGE);
		final int       TS_COL = cursor.getColumnIndexOrThrow(ChatConstants.DATE);
		final int PACKETID_COL = cursor.getColumnIndexOrThrow(ChatConstants.PACKET_ID);
		ContentValues mark_sent = new ContentValues();
		mark_sent.put(ChatConstants.DELIVERY_STATUS, ChatConstants.DS_SENT_OR_READ);
		while (cursor.moveToNext()) {
			long _id = cursor.getLong(_ID_COL);
			String toJID = cursor.getString(JID_COL);
			if (!is_muc && mucJIDs.contains(toJID))
				continue;
			String message = cursor.getString(MSG_COL);
			String packetID = cursor.getString(PACKETID_COL);
			long ts = cursor.getLong(TS_COL);
			Log.d(TAG, "sendOfflineMessages: " + toJID + " > " + message);
			final Message newMessage = new Message(toJID, Message.Type.chat);
			newMessage.setBody(message);
			DelayInformation delay = new DelayInformation(new Date(ts));
			newMessage.addExtension(delay);
			newMessage.addExtension(new DelayInfo(delay));
			if (is_muc)
				newMessage.setType(Message.Type.groupchat);
			else
				newMessage.addExtension(new DeliveryReceiptRequest());
			if ((packetID != null) && (packetID.length() > 0)) {
				newMessage.setPacketID(packetID);
			} else {
				packetID = newMessage.getPacketID();
			}
			mark_sent.put(ChatConstants.PACKET_ID, packetID);
			Uri rowuri = Uri.parse("content://" + ChatProvider.AUTHORITY
				+ "/" + ChatProvider.TABLE_NAME + "/" + _id);
			mContentResolver.update(rowuri, mark_sent,
						null, null);
			mXMPPConnection.sendPacket(newMessage);		// must be after marking delivered, otherwise it may override the SendFailListener
		}
		cursor.close();
	}

	public static void sendOfflineMessage(ContentResolver cr, String toJID, String message) {
		ContentValues values = new ContentValues();
		values.put(ChatConstants.DIRECTION, ChatConstants.OUTGOING);
		values.put(ChatConstants.JID, toJID);
		values.put(ChatConstants.MESSAGE, message);
		values.put(ChatConstants.DELIVERY_STATUS, ChatConstants.DS_NEW);
		values.put(ChatConstants.DATE, System.currentTimeMillis());
		values.put(ChatConstants.PACKET_ID, Packet.nextID());

		cr.insert(ChatProvider.CONTENT_URI, values);
	}

	public void sendReceiptIfRequested(Packet packet) {
		DeliveryReceiptRequest drr = (DeliveryReceiptRequest)packet.getExtension(
				DeliveryReceiptRequest.ELEMENT, DeliveryReceipt.NAMESPACE);
		if (drr != null) {
			Message ack = new Message(packet.getFrom(), Message.Type.normal);
			ack.addExtension(new DeliveryReceipt(packet.getPacketID()));
			mXMPPConnection.sendPacket(ack);
		}
	}

	public void sendMessage(String toJID, String message) {
		final Message newMessage = new Message(toJID, Message.Type.chat);
		newMessage.setBody(message);
		newMessage.addExtension(new DeliveryReceiptRequest());
		if (isAuthenticated()) {

			if(mucJIDs.contains(toJID)) {
				sendMucMessage(toJID, message);
			} else {
				addChatMessageToDB(ChatConstants.OUTGOING, toJID, message, ChatConstants.DS_SENT_OR_READ,
						System.currentTimeMillis(), newMessage.getPacketID());
				mXMPPConnection.sendPacket(newMessage);
			}
		} else {
			// send offline -> store to DB
			addChatMessageToDB(ChatConstants.OUTGOING, toJID, message, ChatConstants.DS_NEW,
					System.currentTimeMillis(), newMessage.getPacketID());
		}
	}

	// method checks whether the XMPP connection is authenticated and fully bound (i.e. after resume/bind)
	public boolean isAuthenticated() {
		if (mXMPPConnection != null) {
			return mXMPPConnection.isConnected() && mXMPPConnection.isAuthenticated() &&
					mState != ConnectionState.CONNECTING;
		}
		return false;
	}

	public void registerCallback(XMPPServiceCallback callBack) {
		this.mServiceCallBack = callBack;
		mService.registerReceiver(mPingAlarmReceiver, new IntentFilter(PING_ALARM));
		mService.registerReceiver(mPongTimeoutAlarmReceiver, new IntentFilter(PONG_TIMEOUT_ALARM));
	}

	public void unRegisterCallback() {
		debugLog("unRegisterCallback()");
		// remove callbacks _before_ tossing old connection
		try {
			mXMPPConnection.getRoster().removeRosterListener(mRosterListener);
			mXMPPConnection.removePacketListener(mPacketListener);
			mXMPPConnection.removePacketListener(mPresenceListener);

			mXMPPConnection.removePacketListener(mPongListener);
			unregisterPongListener();
		} catch (Exception e) {
			// ignore it!
			e.printStackTrace();
		}
		requestConnectionState(ConnectionState.OFFLINE);
		setStatusOffline();
		mService.unregisterReceiver(mPingAlarmReceiver);
		mService.unregisterReceiver(mPongTimeoutAlarmReceiver);
//		multiUserChats.clear(); // TODO: right place
		this.mServiceCallBack = null;
	}
	
	public String getNameForJID(String jid) {
		if (jid.contains("/")) { // MUC-PM
			String[] jid_parts = jid.split("/", 2);
			return String.format("%s (%s)", jid_parts[1],
					ChatRoomHelper.getRoomName(mService, jid_parts[0]));
		}
		RosterEntry re = mRoster.getEntry(jid);
		if (null != re && null != re.getName() && re.getName().length() > 0) {
			return re.getName();
		} else if (mucJIDs.contains(jid)) {
			return ChatRoomHelper.getRoomName(mService, jid);
		} else {
			return jid;
		}			
	}

	public long getRowIdForMessage(String jid, String resource, int direction, String packet_id) {
		// query the DB for the RowID, return -1 if packet_id does not match
		Cursor c = mContentResolver.query(ChatProvider.CONTENT_URI, new String[] { ChatConstants._ID, ChatConstants.PACKET_ID },
				"jid = ? AND resource = ? AND from_me = ?",
				new String[] { jid, resource, "" + direction }, "_id DESC");
		long result = -1;
		if (c.moveToFirst() && c.getString(1).equals(packet_id))
			result = c.getLong(0);
		c.close();
		return result;
	}

	private void setStatusOffline() {
		ContentValues values = new ContentValues();
		values.put(RosterConstants.STATUS_MODE, StatusMode.offline.ordinal());
		mContentResolver.update(RosterProvider.CONTENT_URI, values, null, null);
	}

	private void registerRosterListener() {
		// flush roster on connecting.
		mRoster = mXMPPConnection.getRoster();
		mRoster.setSubscriptionMode(Roster.SubscriptionMode.manual);

		if (mRosterListener != null)
			mRoster.removeRosterListener(mRosterListener);

		mRosterListener = new RosterListener() {
			private boolean first_roster = true;

			public void entriesAdded(Collection<String> entries) {
				debugLog("entriesAdded(" + entries + ")");

				ContentValues[] cvs = new ContentValues[entries.size()];
				int i = 0;
				for (String entry : entries) {
					RosterEntry rosterEntry = mRoster.getEntry(entry);
					cvs[i++] = getContentValuesForRosterEntry(rosterEntry);
				}
				mContentResolver.bulkInsert(RosterProvider.CONTENT_URI, cvs);
				// when getting the roster in the beginning, remove remains of old one
				if (first_roster) {
					removeOldRosterEntries();
					first_roster = false;
				}
				debugLog("entriesAdded() done");
			}

			public void entriesDeleted(Collection<String> entries) {
				debugLog("entriesDeleted(" + entries + ")");

				for (String entry : entries) {
					deleteRosterEntryFromDB(entry);
				}
			}

			public void entriesUpdated(Collection<String> entries) {
				debugLog("entriesUpdated(" + entries + ")");

				for (String entry : entries) {
					RosterEntry rosterEntry = mRoster.getEntry(entry);
					updateRosterEntryInDB(rosterEntry);
				}
			}

			public void presenceChanged(Presence presence) {
				debugLog("presenceChanged(" + presence.getFrom() + "): " + presence);

				String jabberID = getBareJID(presence.getFrom());
				RosterEntry rosterEntry = mRoster.getEntry(jabberID);
				if (rosterEntry != null)
					upsertRoster(getContentValuesForRosterEntry(rosterEntry, presence),
							rosterEntry.getUser());
			}
		};
		mRoster.addRosterListener(mRosterListener);
	}

	private String getBareJID(String from) {
		String[] res = from.split("/", 2);
		return res[0].toLowerCase();
	}

	/* sanitize a jabber ID obtained from a packet:
	 *  - split into bare JID and resource
	 *  - lowercase the bare JID only
	 *  - fallback to correct default value if empty/null (default is dependent on context/session, therefore must be supplied)
	 */
	private String[] getJabberID(String from, String fallback) {
		if (from == null || from.length() == 0)
			from = fallback;
		if(from.contains("/")) {
			String[] res = from.split("/", 2);
			return new String[] { res[0].toLowerCase(), res[1] };
		} else {
			return new String[] {from.toLowerCase(), ""};
		}
	}

	public boolean changeMessageDeliveryStatus(String packetID, int new_status) {
		ContentValues cv = new ContentValues();
		cv.put(ChatConstants.DELIVERY_STATUS, new_status);
		Uri rowuri = Uri.parse("content://" + ChatProvider.AUTHORITY + "/"
				+ ChatProvider.TABLE_NAME);
		return mContentResolver.update(rowuri, cv,
				ChatConstants.PACKET_ID + " = ? AND " +
				ChatConstants.DELIVERY_STATUS + " != " + ChatConstants.DS_ACKED + " AND " +
				ChatConstants.DIRECTION + " = " + ChatConstants.OUTGOING,
				new String[] { packetID }) > 0;
	}

	protected boolean is_user_watching = false;
	public void setUserWatching(boolean user_watching) {
		if (is_user_watching == user_watching)
			return;
		is_user_watching = user_watching;
		if (isAuthenticated())
			sendUserWatching();
	}

	protected void sendUserWatching() {
		IQ toggle_google_queue = new IQ() {
			public String getChildElementXML() {
				// enable g:q = start queueing packets = do it when the user is gone
				return "<query xmlns='google:queue'><" + (is_user_watching ? "disable" : "enable") + "/></query>";
			}
		};
		toggle_google_queue.setType(IQ.Type.SET);
		mXMPPConnection.sendPacket(toggle_google_queue);
	}

	/** Check the server connection, reconnect if needed.
	 *
	 * This function will try to ping the server if we are connected, and try
	 * to reestablish a connection otherwise.
	 */
	public void sendServerPing() {
		if (isAuthenticated()) {
			debugLog("Ping: requested, but not connected to server.");
			requestConnectionState(ConnectionState.ONLINE, false);
			return;
		}
		if (mPingID != null) {
			debugLog("Ping: requested, but still waiting for " + mPingID);
			return; // a ping is still on its way
		}

		if (mStreamHandler.isSmEnabled()) {
			debugLog("Ping: sending SM request");
			mPingID = "" + mStreamHandler.requestAck();
		} else {
			Ping ping = new Ping();
			ping.setType(Type.GET);
			ping.setTo(mConfig.server);
			mPingID = ping.getPacketID();
			debugLog("Ping: sending ping " + mPingID);
			mXMPPConnection.sendPacket(ping);
		}

		// register ping timeout handler: PACKET_TIMEOUT(30s) + 3s
		registerPongTimeout(PACKET_TIMEOUT + 3000, mPingID);
	}

	private void gotServerPong(String pongID) {
		long latency = System.currentTimeMillis() - mPingTimestamp;
		if (pongID != null && pongID.equals(mPingID))
			Log.i(TAG, String.format("Ping: server latency %1.3fs",
						latency/1000.));
		else
			Log.i(TAG, String.format("Ping: server latency %1.3fs (estimated)",
						latency/1000.));
		mPingID = null;
		mAlarmManager.cancel(mPongTimeoutAlarmPendIntent);
	}

	/** Register a "pong" timeout on the connection. */
	private void registerPongTimeout(long wait_time, String id) {
		mPingID = id;
		mPingTimestamp = System.currentTimeMillis();
		debugLog(String.format("Ping: registering timeout for %s: %1.3fs", id, wait_time/1000.));
		mAlarmManager.set(AlarmManager.RTC_WAKEUP,
				System.currentTimeMillis() + wait_time,
				mPongTimeoutAlarmPendIntent);
	}

	/**
	 * BroadcastReceiver to trigger reconnect on pong timeout.
	 */
	private class PongTimeoutAlarmReceiver extends BroadcastReceiver {
		public void onReceive(Context ctx, Intent i) {
			debugLog("Ping: timeout for " + mPingID);
			onDisconnected(mService.getString(R.string.conn_ping_timeout));
		}
	}

	/**
	 * BroadcastReceiver to trigger sending pings to the server
	 */
	private class PingAlarmReceiver extends BroadcastReceiver {
		public void onReceive(Context ctx, Intent i) {
			try {
				sendServerPing();
				// ping all MUCs. if no ping was received since last attempt, /cycle
				Iterator<MUCController> muc_it = multiUserChats.values().iterator();
				long ts = System.currentTimeMillis();
				ContentValues cvR = new ContentValues();
				cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, mService.getString(R.string.conn_ping_timeout));
				cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.offline.ordinal());
				cvR.put(RosterProvider.RosterConstants.GROUP, RosterProvider.RosterConstants.MUCS);
				while (muc_it.hasNext()) {
					MultiUserChat muc = muc_it.next().muc;
					if (!muc.isJoined())
						continue;
					Long lastPong = mucLastPong.get(muc.getRoom());
					if (mucLastPing > 0 && (lastPong == null || lastPong < mucLastPing)) {
						debugLog("Ping timeout from " + muc.getRoom());
						muc.leave();
						upsertRoster(cvR, muc.getRoom());
					} else {
						Ping ping = new Ping();
						ping.setType(Type.GET);
						String jid = muc.getRoom() + "/" + muc.getNickname();
						ping.setTo(jid);
						debugLog("Ping: sending ping to " + jid);
						mXMPPConnection.sendPacket(ping);
					}
				}
				syncDbRooms();
				mucLastPing = ts;
			} catch (NullPointerException npe) {
				/* ignore disconnect race condition */
			} catch (IllegalStateException ise) {
				/* ignore disconnect race condition */
			}
		}
	}

	private boolean isValidPingResponse(IQ response) {
		// a 'result' response means, the other party supports ping and responded appropriately
		if (response.getType() == Type.RESULT)
			return true;
		// 'error' can be caused by s2s issues, non-existing destination, solar flares or one of these two:
		//  * 'service-unavailable': official not-supported response as of RFC6120 (§8.4) and XEP-0199 (§4.1)
		//  * 'feature-not-implemented': inoffcial not-supported response from many clients
		if (response.getType() == Type.ERROR) {
			XMPPError e = response.getError();
			return (e.getType() == XMPPError.Type.CANCEL) &&
				("service-unavailable".equals(e.getCondition()) ||
				 "feature-not-implemented".equals(e.getCondition()));
		}
		return false;
	}

	/**
	 * Registers a smack packet listener for IQ packets, intended to recognize "pongs" with
	 * a packet id matching the last "ping" sent to the server.
	 *
	 * Also sets up the AlarmManager Timer plus necessary intents.
	 */
	private void registerPongListener() {
		// reset ping expectation on new connection
		mPingID = null;

		if (mPongListener != null)
			mXMPPConnection.removePacketListener(mPongListener);

		mPongListener = new PacketListener() {

			@Override
			public void processPacket(Packet packet) {
				if (packet == null) return;

				if (packet instanceof IQ && packet.getFrom() != null) {
					IQ pong = (IQ)packet;
					String[] from = getJabberID(pong.getFrom(), null);
					// check for MUC self-ping response
					if (mucJIDs.contains(from[0]) && from[1].equals(getMyMucNick(from[0]))) {
						if (isValidPingResponse(pong)) {
							Log.d(TAG, "Ping: got response from MUC " + from[0]);
							mucLastPong.put(from[0], System.currentTimeMillis());
						} else if (pong.getError() != null) {
							Log.d(TAG, "Ping: got error from MUC " + from[0] + ": " + pong.getError());
							MUCController muc = multiUserChats.get(from[0]);
							if (muc != null && muc.muc.isJoined()) {
								muc.muc.leave();
								syncDbRooms();
							}
						}
					}
				}
				if (mPingID != null && mPingID.equals(packet.getPacketID()))
					gotServerPong(packet.getPacketID());

			}

		};

		mXMPPConnection.addPacketListener(mPongListener, new PacketTypeFilter(IQ.class));
		mAlarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, 
				System.currentTimeMillis() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, AlarmManager.INTERVAL_FIFTEEN_MINUTES, mPingAlarmPendIntent);
	}
	private void unregisterPongListener() {
		mAlarmManager.cancel(mPingAlarmPendIntent);
		mAlarmManager.cancel(mPongTimeoutAlarmPendIntent);
	}

	private void registerMessageListener() {
		PacketTypeFilter filter = new PacketTypeFilter(Message.class);

		// do not register multiple packet listeners
		if (mPacketListener != null) {
			mXMPPConnection.removePacketListener(mPacketListener);
			mXMPPConnection.addPacketListener(mPacketListener, filter);
			return;
		}

		mPacketListener = new PacketListener() {
			public void processPacket(Packet packet) {
				try {
				if (packet instanceof Message) {
					Message msg = (Message) packet;

					String[] fromJID = getJabberID(msg.getFrom(), mConfig.server);
					
					int direction = ChatConstants.INCOMING;
					Carbon cc = CarbonManager.getCarbon(msg);
					if (cc != null && !msg.getFrom().equalsIgnoreCase(mConfig.jabberID)) {
						Log.w(TAG, "Received illegal carbon from " + msg.getFrom() + ": " + cc.toXML());
						cc = null;
					}

					// extract timestamp
					long ts;
					DelayInfo timestamp = (DelayInfo)msg.getExtension("delay", "urn:xmpp:delay");
					if (timestamp == null)
						timestamp = (DelayInfo)msg.getExtension("x", "jabber:x:delay");
					if (cc != null) // Carbon timestamp overrides packet timestamp
						timestamp = cc.getForwarded().getDelayInfo();
					if (timestamp != null)
						ts = timestamp.getStamp().getTime();
					else
						ts = System.currentTimeMillis();

					// try to extract a carbon
					if (cc != null) {
						Log.d(TAG, "carbon: " + cc.toXML());
						msg = (Message)cc.getForwarded().getForwardedPacket();

						// outgoing carbon: fromJID is actually chat peer's JID
						if (cc.getDirection() == Carbon.Direction.sent) {
							fromJID = getJabberID(msg.getTo(), mConfig.jabberID);
							direction = ChatConstants.OUTGOING;
						} else {
							fromJID = getJabberID(msg.getFrom(), mConfig.server);

							// hook off carbonated delivery receipts
							DeliveryReceipt dr = (DeliveryReceipt)msg.getExtension(
									DeliveryReceipt.ELEMENT, DeliveryReceipt.NAMESPACE);
							if (dr != null) {
								Log.d(TAG, "got CC'ed delivery receipt for " + dr.getId());
								changeMessageDeliveryStatus(dr.getId(), ChatConstants.DS_ACKED);
							}
						}

						// ignore carbon copies of OTR messages sent by broken clients
						if (msg.getBody() != null && msg.getBody().startsWith("?OTR")) {
							Log.i(TAG, "Ignoring OTR carbon from " + msg.getFrom() + " to " + msg.getTo());
							return;
						}
					}

					// check for jabber MUC invitation
					if(direction == ChatConstants.INCOMING && handleMucInvitation(msg)) {
						sendReceiptIfRequested(packet);
						return;
					}

					String chatMessage = msg.getBody();

					// display error inline
					if (msg.getType() == Message.Type.error) {
						if (changeMessageDeliveryStatus(msg.getPacketID(), ChatConstants.DS_FAILED))
							mServiceCallBack.notifyMessage(fromJID, msg.getError().toString(), (cc != null), Message.Type.error);
						else if (mucJIDs.contains(msg.getFrom())) {
							handleKickedFromMUC(msg.getFrom(), false, null,
									msg.getError().toString());
						}
						return; // we do not want to add errors as "incoming messages"
					}

					boolean is_muc = (msg.getType() == Message.Type.groupchat);
					boolean is_from_me = (direction == ChatConstants.OUTGOING) ||
							(is_muc && fromJID[1].equals(getMyMucNick(fromJID[0])));

					// TODO: catch self-CSN to MUC once sent by yaxim
					if (is_from_me) {
						// perform a message-replace on self-sent MUC message, abort further processing
						if (is_muc && matchOutgoingMucReflection(msg, fromJID))
							return;
						Log.d(TAG, "user is active on different device --> Silent mode");
						mServiceCallBack.setGracePeriod(true);
					}

					// ignore empty messages
					if (chatMessage == null) {
						if (msg.getSubject() != null && msg.getType() == Message.Type.groupchat
								&& mucJIDs.contains(fromJID[0])) {
							// this is a MUC subject, update our DB
							ContentValues cvR = new ContentValues();
							cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, msg.getSubject());
							cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.available.ordinal());
							Log.d(TAG, "MUC subject for " + fromJID[0] + " set to: " + msg.getSubject());
							upsertRoster(cvR, fromJID[0]);
							return;
						}
						Log.d(TAG, "empty message.");
						return;
					}


					// carbons are old. all others are new
					int is_new = (cc == null) ? ChatConstants.DS_NEW : ChatConstants.DS_SENT_OR_READ;
					if (msg.getType() == Message.Type.error)
						is_new = ChatConstants.DS_FAILED;

					// handle MUC-PMs: messages from a nick from a known MUC or with
					// an <x> element
					MUCUser muc_x = (MUCUser)msg.getExtension("x", "http://jabber.org/protocol/muc#user");
					boolean is_muc_pm = !is_muc  && !TextUtils.isEmpty(fromJID[1]) &&
							(muc_x != null || mucJIDs.contains(fromJID[0]));

					// TODO: ignoring 'received' MUC-PM carbons, until XSF sorts out shit:
					// - if yaxim is in the MUC, it will receive a non-carbonated copy of
					//   incoming messages, but not of outgoing ones
					// - if yaxim isn't in the MUC, it can't respond anyway
					if (is_muc_pm && !is_from_me && cc != null)
						return;

					if (is_muc_pm) {
						// store MUC-PMs under the participant's full JID, not bare
						//is_from_me = fromJID[1].equals(getMyMucNick(fromJID[0]));
						fromJID[0] = fromJID[0] + "/" + fromJID[1];
						fromJID[1] = null;
						Log.d(TAG, "MUC-PM: " + fromJID[0] + " d=" + direction + " fromme=" + is_from_me);
					}

					// synchronized MUCs and contacts are not silent by default
					boolean is_silent = !(is_muc ? multiUserChats.get(fromJID[0]).isSynchronized : mRoster.contains(fromJID[0]));

					long upsert_id = -1;
					if (is_muc && is_from_me) {
						// messages from our other client are "ACKed" automatically
						is_new = ChatConstants.DS_ACKED;
					}

					// obtain Last Message Correction, if present
					Replace replace = (Replace)msg.getExtension(Replace.NAMESPACE);
					String replace_id = (replace != null) ? replace.getId() : null;

					if (fromJID[0].equalsIgnoreCase(mConfig.jabberID)) {
						// Self-Message, no need to display it twice --> replace old one
						replace_id = msg.getPacketID();
					}
					if (replace_id != null && upsert_id == -1) {
						// obtain row id for last message with that full JID, or -1
						upsert_id = getRowIdForMessage(fromJID[0], fromJID[1], direction, replace_id);
						Log.d(TAG, "Replacing last message from " + fromJID[0] + "/" + fromJID[1] + ": " + replace_id + " -> " + msg.getPacketID());
					}

					if (!is_muc || checkAddMucMessage(msg, msg.getPacketID(), fromJID, timestamp)) {
						addChatMessageToDB(direction, fromJID, chatMessage, is_new, ts, msg.getPacketID(), upsert_id);
						// only notify on private messages or on non-system MUC messages when MUC notification requested
						boolean need_notify = !is_muc || (fromJID[1].length() > 0) && mConfig.needMucNotification(getMyMucNick(fromJID[0]), chatMessage);
						// outgoing carbon -> clear notification by signalling 'null' message
						if (is_from_me) {
							mServiceCallBack.notifyMessage(fromJID, null, true, msg.getType());
							// TODO: MUC PMs
							ChatHelper.markAsRead(mService, fromJID[0]);
						} else if (direction == ChatConstants.INCOMING && need_notify)
							mServiceCallBack.notifyMessage(fromJID, chatMessage, is_silent, msg.getType());
					}
					sendReceiptIfRequested(packet);
				}
				} catch (Exception e) {
					// SMACK silently discards exceptions dropped from processPacket :(
					Log.e(TAG, "failed to process packet:");
					e.printStackTrace();
				}
			}
		};

		mXMPPConnection.addPacketListener(mPacketListener, filter);
	}

	private boolean matchOutgoingMucReflection(Message msg, String[] fromJid) {
		String muc = fromJid[0];
		String nick = fromJid[1];
		String packet_id = msg.getPacketID();
		if (packet_id == null)
			packet_id = "";

		MUCController mucc = multiUserChats.get(muc);
		if (!nick.equals(getMyMucNick(muc)))
			return false;
		// TODO: store pending _id's in MUCController
		// https://stackoverflow.com/a/8248052/539443 - securely use LIKE
		String firstline = msg.getBody().replace("!", "!!")
				.replace("%", "!%")
				.replace("_", "!_")
				.replace("[", "![");
		if (msg.getBody().length() > 400)
			firstline = firstline + "%"; /* prefix match on long lines split for IRC */
		else
			firstline = firstline + "\n%"; /* first line match on other lines */
		Cursor c = mContentResolver.query(ChatProvider.CONTENT_URI, new String[] { ChatConstants._ID, ChatConstants.PACKET_ID },
				"jid = ? AND from_me = 1 AND (pid = ? OR message = ? OR message LIKE ? ESCAPE '!') AND _id >= ? AND read != ?",
				new String[] { muc, packet_id, msg.getBody(), firstline, "" + mucc.getFirstPacketID(), "" + ChatConstants.DS_FAILED }, null);
		boolean updated = false;
		if (c.moveToFirst()) {
			long _id = c.getLong(0);
			ContentValues values = new ContentValues();
			values.put(ChatConstants.RESOURCE, nick);
			values.put(ChatConstants.DIRECTION, ChatConstants.INCOMING);
			values.put(ChatConstants.MESSAGE, msg.getBody());
			values.put(ChatConstants.DELIVERY_STATUS, ChatConstants.DS_ACKED);
			values.put(ChatConstants.PACKET_ID, packet_id);
			updated = mContentResolver.update(Uri.withAppendedPath(ChatProvider.CONTENT_URI, "" + _id),
					values, null, null) == 1;
		}
		c.close();
		return updated;
	}

	private boolean checkAddMucMessage(Message msg, String packet_id, String[] fromJid, DelayInfo timestamp) {
		String muc = fromJid[0];
		String nick = fromJid[1];

		MUCController mucc = multiUserChats.get(muc);
		// messages with no timestamp are always new, and always come after join is completed
		if (timestamp == null) {
			mucc.isSynchronized = true;
			return true;
		}
		// messages after we have joined and synchronized the MUC are always new
		if (mucc.isSynchronized)
			return true;

		long ts = timestamp.getStamp().getTime();

		final String[] projection = new String[] {
				ChatConstants._ID, ChatConstants.MESSAGE,
				ChatConstants.JID, ChatConstants.RESOURCE,
				ChatConstants.PACKET_ID
		};

		if (packet_id == null) packet_id = "";
		// TODO: merge failed messages with re-send attempts when sending, disable DS_FAILED check
		final String selection = "jid = ? AND resource = ? AND (pid = ? OR date = ? OR message = ?) AND _id >= ? AND read != ?";
		final String[] selectionArgs = new String[] { muc, nick, packet_id, ""+ts, msg.getBody(), ""+mucc.getFirstPacketID(), ""+ChatConstants.DS_FAILED };
		try {
			Cursor cursor = mContentResolver.query(ChatProvider.CONTENT_URI, projection, selection, selectionArgs, null);
			Log.d(TAG, "message from " + nick + " matched " + cursor.getCount() + " items.");
			boolean result = (cursor.getCount() == 0);
			cursor.close();
			return result;
		} catch (Exception e) { e.printStackTrace(); } // just return true...
		return true;	
	}

	private void handleKickedFromMUC(String room, boolean banned, String actor, String reason) {
		mucLastPong.remove(room);
		ContentValues cvR = new ContentValues();
		String message;
		if (actor != null && actor.length() > 0)
			message = mService.getString(banned ? R.string.muc_banned_by : R.string.muc_kicked_by,
					actor, reason);
		else
			message = mService.getString(banned ? R.string.muc_banned : R.string.muc_kicked,
					reason);
		cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, message);
		cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.offline.ordinal());
		upsertRoster(cvR, room);
	}

	@Override
	public String getMyMucNick(String jid) {
		MUCController muc = multiUserChats.get(jid);
		if (muc != null && muc.muc.getNickname() != null)
			return muc.muc.getNickname();
		if (mucJIDs.contains(jid)) {
			ChatRoomHelper.RoomInfo ri = ChatRoomHelper.getRoomInfo(mService, jid);
			if (ri != null)
				return ri.nickname;
		}
		return null;
	}

	private void registerPresenceListener() {
		// do not register multiple packet listeners
		if (mPresenceListener != null)
			mXMPPConnection.removePacketListener(mPresenceListener);

		mPresenceListener = new PacketListener() {
			public void processPacket(Packet packet) {
				try {
					Presence p = (Presence) packet;
					switch (p.getType()) {
					case subscribe:
						handleIncomingSubscribe(p);
						break;
					case subscribed:
					case unsubscribe:
					case unsubscribed:
						subscriptionRequests.remove(p.getFrom());
						break;
					}
				} catch (Exception e) {
					// SMACK silently discards exceptions dropped from processPacket :(
					Log.e(TAG, "failed to process presence:");
					e.printStackTrace();
				}
			}
		};

		mXMPPConnection.addPacketListener(mPresenceListener, new PacketTypeFilter(Presence.class));
	}

	private void addChatMessageToDB(int direction, String[] tJID,
			String message, int delivery_status, long ts, String packetID, long upsert_id) {
		ContentValues values = new ContentValues();

		values.put(ChatConstants.DIRECTION, direction);
		values.put(ChatConstants.JID, tJID[0]);
		values.put(ChatConstants.RESOURCE, tJID[1]);
		values.put(ChatConstants.MESSAGE, message);
		values.put(ChatConstants.DELIVERY_STATUS, delivery_status);
		values.put(ChatConstants.DATE, ts);
		values.put(ChatConstants.PACKET_ID, packetID);

		if (upsert_id >= 0 &&
		    mContentResolver.update(Uri.withAppendedPath(ChatProvider.CONTENT_URI, "" + upsert_id),
				values, null, null) == 1)
			return;
		Uri res = mContentResolver.insert(ChatProvider.CONTENT_URI, values);
		MUCController mucc = multiUserChats.get(tJID[0]);
		if (mucc != null)
			mucc.addPacketID(res);
	}

	private void addChatMessageToDB(int direction, String JID,
			String message, int delivery_status, long ts, String packetID) {
		String[] tJID = {JID, ""};
		addChatMessageToDB(direction, tJID, message, delivery_status, ts, packetID, -1);
	}

	private ContentValues getContentValuesForRosterEntry(final RosterEntry entry) {
		Presence presence = mRoster.getPresence(entry.getUser());
		return getContentValuesForRosterEntry(entry, presence);
	}

	private ContentValues getContentValuesForRosterEntry(final RosterEntry entry, Presence presence) {
		final ContentValues values = new ContentValues();

		values.put(RosterConstants.JID, entry.getUser());
		values.put(RosterConstants.ALIAS, getName(entry));

		// handle subscription requests and errors with higher priority
		Presence sub = subscriptionRequests.get(entry.getUser());
		if (presence.getType() == Presence.Type.error) {
			String error = presence.getError().getMessage();
			if (error == null || error.length() == 0)
				error = presence.getError().toString();
			values.put(RosterConstants.STATUS_MESSAGE, error);
		} else if (sub != null) {
			presence = sub;
			values.put(RosterConstants.STATUS_MESSAGE, presence.getStatus());
		} else switch (entry.getType()) {
		case to:
		case both:
			// override received presence from roster, using highest-prio entry
			presence = mRoster.getPresence(entry.getUser());
			values.put(RosterConstants.STATUS_MESSAGE, presence.getStatus());
			break;
		case from:
			values.put(RosterConstants.STATUS_MESSAGE, mService.getString(R.string.subscription_status_from));
			presence = null;
			break;
		case none:
			values.put(RosterConstants.STATUS_MESSAGE, "");
			presence = null;
		}
		values.put(RosterConstants.STATUS_MODE, getStatusInt(presence));
		values.put(RosterConstants.GROUP, getGroup(entry.getGroups()));

		return values;
	}

	private void deleteRosterEntryFromDB(final String jabberID) {
		int count = mContentResolver.delete(RosterProvider.CONTENT_URI,
				RosterConstants.JID + " = ?", new String[] { jabberID });
		debugLog("deleteRosterEntryFromDB: Deleted " + count + " entries");
	}

	private void updateRosterEntryInDB(final RosterEntry entry) {
		upsertRoster(getContentValuesForRosterEntry(entry), entry.getUser());
	}

	private void upsertRoster(final ContentValues values, String jid) {
		if (mContentResolver.update(RosterProvider.CONTENT_URI, values,
				RosterConstants.JID + " = ?", new String[] { jid }) == 0) {
			mContentResolver.insert(RosterProvider.CONTENT_URI, values);
		}
	}

	private String getGroup(Collection<RosterGroup> groups) {
		for (RosterGroup group : groups) {
			return group.getName();
		}
		return "";
	}

	private String getName(RosterEntry rosterEntry) {
		String name = rosterEntry.getName();
		if (name != null && name.length() > 0) {
			return name;
		}
		return rosterEntry.getUser();
	}

	private StatusMode getStatus(Presence presence) {
		if (presence == null)
			return StatusMode.unknown;
		if (presence.getType() == Presence.Type.subscribe)
			return StatusMode.subscribe;
		if (presence.getType() == Presence.Type.available) {
			if (presence.getMode() != null) {
				return StatusMode.valueOf(presence.getMode().name());
			}
			return StatusMode.available;
		}
		return StatusMode.offline;
	}

	private int getStatusInt(final Presence presence) {
		return getStatus(presence).ordinal();
	}

	private void debugLog(String data) {
		if (LogConstants.LOG_DEBUG) {
			Log.d(TAG, data);
		}
	}

	@Override
	public String getLastError() {
		return mLastError;
	}

	private void discoverMUCDomain(String jid, DiscoverInfo info) {
		if (mConfig.mucDomain != null)
			return;

		Iterator<DiscoverInfo.Identity> identities = info.getIdentities();
		while (identities.hasNext()) {
			DiscoverInfo.Identity identity = identities.next();
			// only accept conference/text, not conference/irc!
			if (identity.getCategory().equals("conference") && identity.getType().equals("text")) {
				mConfig.mucDomain = jid;
				Log.d(TAG, "discoverMUCDomain: " + mConfig.mucDomain);
				return;
			}
		}
	}
	private void loadMUCBookmarks() {
		try {
			Iterator<BookmarkedConference> it = BookmarkManager.getBookmarkManager(mXMPPConnection).getBookmarkedConferences().iterator();
			ArrayList<String> bookmarked_jids = new ArrayList<String>();
			boolean added = false;
			while (it.hasNext()) {
				BookmarkedConference bookmark = it.next();
				bookmarked_jids.add(bookmark.getJid());
				if (!ChatRoomHelper.isRoom(mService, bookmark.getJid())) {
					String jid = bookmark.getJid();
					String nickname = bookmark.getNickname();
					if (TextUtils.isEmpty(nickname))
						nickname = ChatRoomHelper.guessMyNickname(mService, mConfig.userName);
					Log.d(TAG, "Adding MUC: " + jid + "/" + nickname + " join=" + bookmark.isAutoJoin());
					ChatRoomHelper.addRoom(mService, jid, bookmark.getPassword(), nickname, bookmark.isAutoJoin());
					added = true;
				}
			}
			ChatRoomHelper.cleanupUnimportantRooms(mService, bookmarked_jids);
			if (added)
				syncDbRooms();
		} catch (XMPPException e) {
			Log.d(TAG, "getBookmarks failed: " + e.getMessage());
		}
	}

	private void discoverServicesAsync() {
		new Thread() {
			public void run() {
				discoverServices();
				loadMUCBookmarks(); // XXX: hack
			}
		}.start();
	}

	private void discoverFileUpload(String jid, DiscoverInfo info) {
		if (mConfig.fileUploadDomain != null)
			return;
		Iterator<DiscoverInfo.Identity> identities = info.getIdentities();
		while (identities.hasNext()) {
			DiscoverInfo.Identity identity = identities.next();
			if (identity.getCategory().equals("store") && identity.getType().equals("file")) {
				mConfig.fileUploadDomain = jid;
			}
		}
		if (mConfig.fileUploadDomain != null) {
			DataForm dataForm = (DataForm) info.getExtension("x", "jabber:x:data");
			if (dataForm != null) {
				Iterator<FormField> fields = dataForm.getFields();
				while (fields.hasNext()) {
					FormField field = fields.next();
					if (field.getVariable().equals("max-file-size")) {
						try {
							mConfig.fileUploadSizeLimit = Long.parseLong(field.getValues().next());
						} catch (NumberFormatException nfe) {
							mConfig.fileUploadSizeLimit = 0;
						}
					}
				}
			}
			Log.i(TAG, "HTTP Upload at " + mConfig.fileUploadDomain + " with limit=" + mConfig.fileUploadSizeLimit);
		}
	}
	private void discoverServices(ServiceDiscoveryManager sdm, String jid) {
		try {
			DiscoverInfo info = sdm.discoverInfo(jid);
			discoverMUCDomain(jid, info);
			discoverFileUpload(jid, info);
		} catch (Exception e) {
			Log.e(TAG, "Error response from " + jid + ": " + e.getLocalizedMessage());
		}
	}
	private void discoverServices() {
		try {
			ServiceDiscoveryManager serviceDiscoveryManager = ServiceDiscoveryManager.getInstanceFor(mXMPPConnection);
			discoverServices(serviceDiscoveryManager, mConfig.server);
			DiscoverItems items = serviceDiscoveryManager.discoverItems(mConfig.server);

			Iterator<DiscoverItems.Item> it = items.getItems();
			while (it.hasNext() && mConfig.fileUploadDomain == null) {
				DiscoverItems.Item item = it.next();
				String jid = item.getEntityID();
				discoverServices(serviceDiscoveryManager, jid);
			}
		} catch (Exception e) {
			Log.e(TAG, "Error discovering services: " + e.getLocalizedMessage());
		}
	}


	private synchronized void cleanupMUCs(boolean set_offline) {
		// get a fresh MUC list
		Cursor cursor = mContentResolver.query(RosterProvider.MUCS_URI,
				new String[] { RosterProvider.RosterConstants.JID },
				"autojoin=1", null, null);
		mucJIDs.clear();
		while(cursor.moveToNext()) {
			mucJIDs.add(cursor.getString(0));
		}
		cursor.close();

		// delete removed MUCs
		StringBuilder exclusion = new StringBuilder(RosterProvider.RosterConstants.GROUP + " = ? AND "
				+ RosterConstants.JID + " NOT IN ('");
		exclusion.append(TextUtils.join("', '", mucJIDs));
		exclusion.append("');");
		mContentResolver.delete(RosterProvider.CONTENT_URI,
				exclusion.toString(),
				new String[] { RosterProvider.RosterConstants.MUCS });
		if (set_offline) {
			// update all other MUCs as offline
			ContentValues values = new ContentValues();
			values.put(RosterConstants.STATUS_MODE, StatusMode.offline.ordinal());
			mContentResolver.update(RosterProvider.CONTENT_URI, values, RosterProvider.RosterConstants.GROUP + " = ?",
					new String[] { RosterProvider.RosterConstants.MUCS });
		}
	}

	public synchronized void syncDbRooms() {
		if (!isAuthenticated()) {
			debugLog("syncDbRooms: aborting, not yet authenticated");
		}

		java.util.Set<String> joinedRooms = multiUserChats.keySet();
		Cursor cursor = mContentResolver.query(RosterProvider.MUCS_URI, 
				new String[] {RosterProvider.RosterConstants._ID,
					RosterProvider.RosterConstants.JID, 
					RosterProvider.RosterConstants.PASSWORD, 
					RosterProvider.RosterConstants.NICKNAME}, 
				"autojoin=1", null, null);
		final int ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants._ID);
		final int JID_ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants.JID);
		final int PASSWORD_ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants.PASSWORD);
		final int NICKNAME_ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants.NICKNAME);
		
		mucJIDs.clear();
		while(cursor.moveToNext()) {
			int id = cursor.getInt(ID);
			String jid = cursor.getString(JID_ID);
			String password = cursor.getString(PASSWORD_ID);
			String nickname = cursor.getString(NICKNAME_ID);
			mucJIDs.add(jid);
			//debugLog("Found MUC Room: "+jid+" with nick "+nickname+" and pw "+password);
			if(!joinedRooms.contains(jid) || !multiUserChats.get(jid).muc.isJoined()) {
				debugLog("room " + jid + " isn't joined yet, i wanna join...");
				joinRoomAsync(jid, nickname, password); // TODO: make historyLen configurable
			} else {
				MultiUserChat muc = multiUserChats.get(jid).muc;
				if (!muc.getNickname().equals(nickname)) {
					debugLog("room " + jid + ": changing nickname to " + nickname);
					try {
						muc.changeNickname(nickname);
					} catch (XMPPException e) {
						Log.e(TAG, "Changing nickname failed.");
						e.printStackTrace();
					}
				}
			}
			//debugLog("found data in contentprovider: "+jid+" "+password+" "+nickname);
		}
		cursor.close();
		
		for(String room : new HashSet<String>(joinedRooms)) {
			if(!mucJIDs.contains(room)) {
				quitRoom(room);
			}
		}
		cleanupMUCs(false);
	}
	
	protected boolean handleMucInvitation(Message msg) {
		String room;
		String inviter = null;
		String reason = null;
		String password = null;
		
		MUCUser mucuser = (MUCUser)msg.getExtension("x", "http://jabber.org/protocol/muc#user");
		GroupChatInvitation direct = (GroupChatInvitation)msg.getExtension(GroupChatInvitation.ELEMENT_NAME, GroupChatInvitation.NAMESPACE);
		if (mucuser != null && mucuser.getInvite() != null) {
			// first try official XEP-0045 mediated invitation
			MUCUser.Invite invite = mucuser.getInvite();
			room = msg.getFrom();
			inviter = invite.getFrom();
			reason = invite.getReason();
			password = mucuser.getPassword();
		} else if (direct != null) {
			// fall back to XEP-0249 direct invitation
			room = direct.getRoomAddress();
			inviter = msg.getFrom();
			// TODO: get reason from direct invitation, not supported in smack3
		} else return false; // not a MUC invitation

		if (mucJIDs.contains(room)) {
			Log.i(TAG, "Ignoring invitation to known MUC " + room);
			return true;
		}
		Log.d(TAG, "MUC invitation from " + inviter + " to " + room);
		asyncProcessMucInvitation(room, inviter, reason, password);
		return true;
	}

	protected void asyncProcessMucInvitation(final String room, final String inviter,
			final String reason, final String password) {
		new Thread() {
			public void run() {
				processMucInvitation(room, inviter, reason, password);
			}
		}.start();
	}
	protected void processMucInvitation(final String room, final String inviter,
			final String reason, final String password) {
		String roomname = room;
		String inviter_name = null;
		if (getBareJID(inviter).equalsIgnoreCase(room)) {
			// from == participant JID, display as "user (MUC)"
			inviter_name = getNameForJID(inviter);
		} else {
			// from == user bare or full JID
			inviter_name = getNameForJID(getBareJID(inviter));
		}
		String description = null;
		String inv_from = mService.getString(R.string.muc_invitation_from,
				inviter_name);

		// query room for info
		try {
			Log.d(TAG, "Requesting disco#info from " + room);
			RoomInfo ri = MultiUserChat.getRoomInfo(mXMPPConnection, room);
			String rn = ri.getRoomName();
			if (rn != null && rn.length() > 0)
				roomname = String.format("%s (%s)", rn, roomname);
			description = ri.getSubject();
			if (!TextUtils.isEmpty(description))
				description = ri.getDescription();
			description = mService.getString(R.string.muc_invitation_occupants,
					description, ri.getOccupantsCount());
			Log.d(TAG, "MUC name after disco: " + roomname);
		} catch (XMPPException e) {
			// ignore a failed room info request
			Log.d(TAG, "MUC room IQ failed: " + room);
			e.printStackTrace();
		}

		mServiceCallBack.mucInvitationReceived(
				roomname,
				room,
				password,
				inv_from,
				description);
	}
	
	private Map<String,Runnable> ongoingMucJoins = new java.util.concurrent.ConcurrentHashMap<String, Runnable>();
	private synchronized void joinRoomAsync(final String room, final String nickname, final String password) {
		if (ongoingMucJoins.containsKey(room))
			return;
		Thread joiner = new Thread() {
			@Override
			public void run() {
				Log.d(TAG, "async joining " + room);
				boolean result = joinRoom(room, nickname, password);
				Log.d(TAG, "async joining " + room + " done: " + result);
				ongoingMucJoins.remove(room);
			}
		};
		ongoingMucJoins.put(room, joiner);
		joiner.start();
	}

	private boolean joinRoom(final String room, String nickname, String password) {
		// work around smack3 bug: can't rejoin with "used" MultiUserChat instance; need to manually
		// flush old MUC instance and create a new.
		MUCController mucc = multiUserChats.get(room);
		if (mucc != null)
			mucc.cleanup();
		mucc = new MUCController(mXMPPConnection, room);
		MultiUserChat muc = mucc.muc;
		mucc.loadPacketIDs(mContentResolver);

		Log.d(TAG, "created new MUC instance: " + room + " " + muc);
		muc.addUserStatusListener(new org.jivesoftware.smackx.muc.DefaultUserStatusListener() {
			@Override
			public void kicked(String actor, String reason) {
				debugLog("Kicked from " + room + " by " + actor + ": " + reason);
				handleKickedFromMUC(room, false, actor, reason);
			}
			@Override
			public void banned(String actor, String reason) {
				debugLog("Banned from " + room + " by " + actor + ": " + reason);
				handleKickedFromMUC(room, true, actor, reason);
			}
		});
		
		DiscussionHistory history = new DiscussionHistory();
		final String[] projection = new String[] {
				ChatConstants._ID, ChatConstants.DATE
		};
		Cursor cursor = mContentResolver.query(ChatProvider.CONTENT_URI, projection, 
				ChatConstants.JID + " = ? AND " +
				ChatConstants.DIRECTION + " = " + ChatConstants.INCOMING,
				new String[] { room }, "_id DESC LIMIT 1");
		if(cursor.getCount()>0) {
			cursor.moveToFirst();
			Date lastDate = new Date(cursor.getLong(1));
			Log.d(TAG, "Getting room history for " + room + " starting at " + lastDate);
			history.setSince(lastDate);
		} else Log.d(TAG, "Getting room history for " + room + " (full history)");
		cursor.close();
		
		ContentValues cvR = new ContentValues();
		cvR.put(RosterProvider.RosterConstants.JID, room);
		cvR.put(RosterProvider.RosterConstants.ALIAS, room);
		cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, mService.getString(R.string.muc_synchronizing));
		cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.dnd.ordinal());
		cvR.put(RosterProvider.RosterConstants.GROUP, RosterProvider.RosterConstants.MUCS);
		upsertRoster(cvR, room);
		cvR.clear();
		cvR.put(RosterProvider.RosterConstants.JID, room);
		try {
			Presence force_resync = new Presence(Presence.Type.unavailable);
			force_resync.setTo(room + "/" + nickname);
			mXMPPConnection.sendPacket(force_resync);
			muc.join(nickname, password, history, 10*PACKET_TIMEOUT);
		} catch (Exception e) {
			Log.e(TAG, "Could not join MUC-room "+room);
			e.printStackTrace();
			// work around race condition when MUC was removed while joining
			if(mucJIDs.contains(room)) {
				cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, mService.getString(R.string.conn_error, e.getLocalizedMessage()));
				cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.offline.ordinal());
				upsertRoster(cvR, room);
			}
			muc.cleanup();
			return false;
		}

		if(muc.isJoined()) {
			synchronized(this) {
				multiUserChats.put(room, mucc);
			}
			String roomname = room.split("@")[0];
			try {
				RoomInfo ri = MultiUserChat.getRoomInfo(mXMPPConnection, room);
				String rn = ri.getRoomName();
				if (rn != null && rn.length() > 0)
					roomname = rn;
				Log.d(TAG, "MUC name after disco: " + roomname);
			} catch (XMPPException e) {
				// ignore a failed room info request
				Log.d(TAG, "MUC room IQ failed: " + room);
				e.printStackTrace();
			}
			// delay requesting subject until room info IQ returned/failed
			String subject = muc.getSubject();
			cvR.put(RosterProvider.RosterConstants.ALIAS, roomname);
			cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, subject);
			cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.available.ordinal());
			Log.d(TAG, "upserting MUC as online: " + roomname);
			upsertRoster(cvR, room);
			sendOfflineMessages(room);
			return true;
		}
		
		muc.cleanup();
		return false;
	}

	@Override
	public void sendMucMessage(String room, String message) {
		Message newMessage = new Message(room, Message.Type.groupchat);
		newMessage.setBody(message);
		addChatMessageToDB(ChatConstants.OUTGOING, room, message, ChatConstants.DS_NEW,
				System.currentTimeMillis(), newMessage.getPacketID());
		mXMPPConnection.sendPacket(newMessage);
	}

	private void quitRoom(String room) {
		Log.d(TAG, "Leaving MUC " + room);
		MultiUserChat muc = multiUserChats.get(room).muc;
		muc.leave();
		multiUserChats.remove(room);
		mucLastPong.remove(room);
		mContentResolver.delete(RosterProvider.CONTENT_URI, "jid = ?", new String[] {room});
	}

	@Override
	public boolean inviteToRoom(String contactJid, String roomJid) {
		MultiUserChat muc = multiUserChats.get(roomJid).muc;
		if(contactJid.contains("/")) {
			contactJid = contactJid.split("/")[0];
		}
		Log.d(TAG, "invitng contact: "+contactJid+" to room: "+muc);
		muc.invite(contactJid, "User "+contactJid+" has invited you to a chat!");
		return false;
	}

	@Override
	public List<ParcelablePresence> getUserList(String jid) {
		MUCController mucc = multiUserChats.get(jid);
		if (mucc == null) {
			return null;
		}
		MultiUserChat muc = mucc.muc;
		Log.d(TAG, "MUC instance: " + jid + " " + muc);
		Iterator<String> occIter = muc.getOccupants();
		ArrayList<ParcelablePresence> tmpList = new ArrayList<ParcelablePresence>();
		while(occIter.hasNext()) {
			ParcelablePresence pp = new ParcelablePresence(muc.getOccupantPresence(occIter.next()));
			// smack3 bug: work around nameless participant from ejabberd MUC vcard
			if (!TextUtils.isEmpty(pp.resource))
				tmpList.add(pp);
		}
		Collections.sort(tmpList, new Comparator<ParcelablePresence>() {
			@Override
			public int compare(ParcelablePresence lhs, ParcelablePresence rhs) {
				return java.text.Collator.getInstance().compare(lhs.resource, rhs.resource);
			}
		});
		Log.d(TAG, "getUserList(" + jid + "): " + tmpList.size());
		return tmpList;
	}

	@Override
	public XMPPConnection getConnection() {
		return mXMPPConnection;
	}

}
