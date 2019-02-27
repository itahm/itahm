package com.itahm;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

import com.itahm.http.HTTPListener;
import com.itahm.http.HTTPServer;
import com.itahm.http.Request;
import com.itahm.http.Connection;
import com.itahm.http.Response;
import com.itahm.http.Session;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;

public class ITAhM extends HTTPServer implements HTTPListener {

	private byte [] event = null;
	private final static int SESS_TIMEOUT = 60;
	
	public ITAhM(JSONObject config) throws IOException {
		super(config);
		
		System.out.format("ITAhM HTTP Server started with TCP %d.\n", config.getInt("tcp"));
	}

	public void init(JSONObject config) {
		System.setErr(
			new PrintStream(
				new OutputStream() {

					@Override
					public void write(int b) throws IOException {
					}	
				}
			) {
		
				@Override
				public void print(Object e) {
					((Exception)e).printStackTrace(System.out);
				}
			}
		);
		
		if (config.has("license")) {
			try {
				long l = Long.parseLong(config.getString("license"), 16);
				byte [] license = new byte[6];
				
				for (int i=6; i>0; l>>=8) {
					license[--i] = (byte)(0xff & l);
				}
				
				if (!Agent.isValidLicense(license)) {
					System.out.println("Check your License.MAC");
					
					return;
				}
			} catch (NumberFormatException nfe) {}
		}
		
		if (config.has("expire")) {
			try {
				long expire = Long.parseLong(config.getString("expire"));
				
				if (Calendar.getInstance().getTimeInMillis() > expire) {
					System.out.println("Check your License.Expire");
					
					return;
				}
	
				new Timer().schedule(new TimerTask() {
					
					@Override
					public void run() {
						System.out.println("Check your License.Expire");
						
						close();
					}
				}, new Date(expire));
				
				Agent.Config.expire(expire);
			} catch (NumberFormatException nfe) {}
		}
		
		if (config.has("limit")) {
			try {
				int limit = Integer.parseInt(config.getString("limit"));
				
				Agent.Config.limit(limit);
			} catch (NumberFormatException nfe) {}
		}
		
		if (config.has("root")) {
			File root = new File(config.getString("root"));
			
			if (!root.isDirectory()) {
				System.out.println("Check your Configuration.Root");
				
				return;
			}
		
			System.out.format("Root : %s\n", root.getAbsoluteFile());
			
			Agent.Config.root(root);
		}
		else {
			System.out.println("Check your Configuration.Root");
			
			return;
		}
		
		System.out.format("Agent loading...\n");
		
		Agent.Config.listener(this);
		
		try {	
			Agent.start();
		} catch (IOException ioe) {
			System.err.print(ioe);
			
			return;
		}
	
		System.out.println("ITAhM agent has been successfully started.");
	}
	
	@Override
	public void doGet(Request request, Response response) {
		String uri = request.getRequestURI();
		File file = new File(Agent.Config.root().getParentFile(), uri);
		
		if (!Pattern.compile("^/data/.*").matcher(uri).matches() && file.isFile()) {
			try {
				response.write(file);
			} catch (IOException e) {
				response.setStatus(Response.Status.SERVERERROR);
			}
		}
		else {
			response.setStatus(Response.Status.NOTFOUND);
		}
	}
	
	@Override
	public void doPost(Request request, Response response) {		
		String origin = request.getHeader(Connection.Header.ORIGIN.toString());
		
		if (origin != null) {
			response.setHeader("Access-Control-Allow-Origin", origin);
			response.setHeader("Access-Control-Allow-Credentials", "true");
		}
		
		if (!Agent.ready) {
			response.setStatus(Response.Status.UNAVAILABLE);
			
			return;
		}
		
		JSONObject data;
		
		try {
			data = new JSONObject(new String(request.read(), StandardCharsets.UTF_8.name()));
			
			if (!data.has("command")) {
				throw new JSONException("Command not found.");
			}
			
			Session session = request.getSession(false);
			
			switch (data.getString("command").toLowerCase()) {
			case "signin":
				JSONObject account = null;
				
				if (session == null) {
					account = Agent.signIn(data);
					
					if (account == null) {
						response.setStatus(Response.Status.UNAUTHORIZED);
					}
					else {
						session = request.getSession();
					
						session.setAttribute("account", account);
						session.setMaxInactiveInterval(SESS_TIMEOUT);
					}
				}
				else {
					account = (JSONObject)session.getAttribute("account");
				}
				
				if (account != null) {
					response.write(account.toString());
				}
				
				break;
				
			case "signout":
				if (session != null) {
					session.invalidate();
				}
				
				break;
				
			case "listen":
				if (session == null) {
					response.setStatus(Response.Status.UNAUTHORIZED);
				}
				else {
					JSONObject event = null;
					
					if (data.has("index")) {
						event = Agent.getEvent(data.getLong("event"));
						
					}
					
					if (event == null) {
						synchronized(this) {
							try {
								wait();
							} catch (InterruptedException ie) {
							}
							
							response.write(this.event);
						}
					}
					else {
						response.write(event.toString().getBytes(StandardCharsets.UTF_8.name()));
					}
				}
				
				break;
				
			default:
				if (session == null) {
					response.setStatus(Response.Status.UNAUTHORIZED);
				}
				else if (!Agent.request(data, response)) {
					throw new JSONException("Command not found.");
				}
			}
					
		} catch (JSONException | UnsupportedEncodingException e) {
			response.setStatus(Response.Status.BADREQUEST);
			
			response.write(new JSONObject().
				put("error", e.getMessage()).toString());
		}
	}
	
	public void close() {
		try {
			super.close();
			
			Agent.stop();
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
	}
	
	public static void main(String[] args) throws Exception {
		JSONObject config = new JSONObject()
			//.put("expire", "0")
			//.put("limit", "0")
			//.put("license", "XXXXXXXXXXXX")
			;
		
		for (int i=0, _i=args.length; i<_i; i++) {
			if (args[i].indexOf("-") != 0) {
				continue;
			}
			
			switch(args[i].substring(1).toUpperCase()) {
			case "ROOT":
				config.put("root", args[++i]);
				
				break;
			case "TCP":
				try {
					config.put("tcp", Integer.parseInt(args[++i]));
				}
				catch (NumberFormatException nfe) {}
				
				break;
			}
		}
		
		config.put("root", "F:\\ITAhM\\project\\demo\\kt\\2019");
		
		if (!config.has("root")) {
			config.put("root", new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent());
		}
		
		ITAhM itahm = new ITAhM(config);
		
		Runtime.getRuntime().addShutdownHook(
			new Thread() {
				public void run() {
					itahm.close();
				}
			}
		);
	}

	@Override
	public void sendEvent(JSONObject event, boolean broadcast) {
		synchronized(this) {
			try {
				this.event = event.toString().getBytes(StandardCharsets.UTF_8.name());
				
				notifyAll();
			} catch (UnsupportedEncodingException e) {}			
		}
		
		if (broadcast) {
			// TODO customize for sms or app
		}
	}
}
