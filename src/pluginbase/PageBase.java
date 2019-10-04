/*
 * Plugin Base Package
 * Copyright (C) 2012 Jeriadoc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package pluginbase;

import freenet.l10n.NodeL10n;
import pluginbase.de.todesbaum.util.freenet.fcp2.Message;
import freenet.clients.http.InfoboxNode;
import freenet.clients.http.PageNode;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.pluginmanager.*;
import freenet.support.api.HTTPRequest;
import freenet.support.HTMLNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.util.TreeMap;
import java.util.ArrayList;

import static java.nio.charset.StandardCharsets.*;

abstract public class PageBase extends Toadlet implements FredPluginL10n {

	public PluginBase plugin;

	protected FcpCommands fcp;

	private PageNode page;
	private ArrayList<HTMLNode> vBoxes = new ArrayList();
	private TreeMap<String, Message> mMessages = new TreeMap<>();
	private String strPageName;
	private String strPageTitle;
	private String strRefreshTarget;
	private int nRefreshPeriod = -1;
	private URI uri;
	private HTTPRequest httpRequest;
	private TreeMap<String, String> mRedirectURIs = new TreeMap<>();
	private String strRedirectURI;
	private boolean bFullAccessHostsOnly;

	public PageBase(String cPageName, String cPageTitle, PluginBase plugin, boolean bFullAccessHostsOnly) {
		super(plugin.pluginContext.node.clientCore.makeClient((short) 3, false, false));

		try {
			this.strPageName = cPageName;
			this.strPageTitle = cPageTitle;
			this.plugin = plugin;
			this.bFullAccessHostsOnly = bFullAccessHostsOnly;

			// register this page and add to menu
			plugin.webInterface.registerInvisible(this);
			plugin.log("page '" + cPageName + "' registered");

			// fcp request object
			fcp = new FcpCommands(plugin.fcpConnection, this);

		} catch (Exception e) {
			plugin.log("PageBase(): " + e.getMessage(), 1);
		}
	}

	public String getName() {
		return strPageName;
	}

	@Override
	public String path() {
		return plugin.getPath() + "/" + strPageName;
	}

	/**
	 *
	 * @param cKey
	 * @return
	 */
	@Override
	public String getString(String cKey) {       // FredPluginL10n
		return plugin.getString(cKey);
	}

	/**
	 *
	 * @param newLanguage
	 */
	@Override
	public void setLanguage(LANGUAGE newLanguage) {      // FredPluginL10n
		plugin.setLanguage(newLanguage);
	}

	@Override
	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		try {

			vBoxes.clear();
			if (!bFullAccessHostsOnly || ctx.isAllowedFullAccess()) {
				this.uri = uri;
				this.httpRequest = request;
				handleRequest();
			} else {
				addBox("Access denied!", "Access to this page for hosts with full access rights only.", null);
			}

			makePage(uri, ctx);

		} catch (Exception e) {
			log("PageBase.handleMethodGET(): " + e.getMessage(), 1);
		}
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		handleMethodGET(uri, request, ctx);
	}

	@Override
	public boolean allowPOSTWithoutPassword() {
		return true;
	}

	private String getIdentifier(Message message) throws Exception {
		try {

			String[] aIdentifier = message.getIdentifier().split("_");
			String cIdentifier = aIdentifier[1];
			for (int i = 2; i < aIdentifier.length - 1; i++) {
				cIdentifier += "_" + aIdentifier[i];
			}
			return cIdentifier;

		} catch (Exception e) {
			throw new Exception("PageBase.getIdentifier(): " + e.getMessage());
		}
	}

	void addMessage(Message message) throws Exception {
		try {

			// existing message with same id becomes replaced (e.g. AllData replaces DataFound)
			mMessages.put(getIdentifier(message), message);

		} catch (Exception e) {
			throw new Exception("PageBase.addMessage(): " + e.getMessage());
		}
	}

	void updateRedirectUri(Message message) throws Exception {
		try {

			// existing RedirectUri with same id becomes replaced
			mRedirectURIs.put(getIdentifier(message), message.get("RedirectUri"));

		} catch (Exception e) {
			throw new Exception("PageBase.updateRedirectUri(): " + e.getMessage());
		}
	}

	private void makePage(URI uri, ToadletContext ctx) throws Exception {
		try {

			String path = uri.getPath().substring(plugin.getPath().length());
			if (path.startsWith("/static/")) {
				path = "/resources" + path;
				try (InputStream inputStream = getClass()
						.getResourceAsStream(path)) {
					if (inputStream == null) {
						this.sendErrorPage(ctx, 404,
								NodeL10n.getBase().getString("StaticToadlet.pathNotFoundTitle"),
								NodeL10n.getBase().getString("StaticToadlet.pathNotFound"));
						return;
					}

					String mimeType = URLConnection.guessContentTypeFromStream(inputStream);

					ByteArrayOutputStream content = new ByteArrayOutputStream();
					int len;
					byte[] contentBytes = new byte[1024];
					while ((len = inputStream.read(contentBytes)) != -1) {
						content.write(contentBytes, 0, len);
					}

					writeReply(ctx, 200, mimeType, "", content.toByteArray(), 0, content.size());
					return;
				}
			}

			page = plugin.pagemaker.getPageNode(strPageTitle, ctx);

			// refresh page
			if (nRefreshPeriod != -1) {
				if (strRefreshTarget == null) {
					strRefreshTarget = uri.getPath();
					if (uri.getQuery() != null) {
						strRefreshTarget += "?" + uri.getQuery();
					}
				}
				page.headNode.addChild("meta", new String[]{"http-equiv", "content"},
						new String[]{"refresh", nRefreshPeriod + ";URL=" + strRefreshTarget});
			}

			page.headNode.addChild("link",
					new String[]{"rel", "href", "type"},
					new String[]{"stylesheet", "static/style.css", "text/css"});

			// boxes
			for (HTMLNode box : vBoxes) {
				page.content.addChild(box);
			}

			// write
			writeHTMLReply(ctx, 200, "OK", page.outer.generate());

		} catch (ToadletContextClosedException | IOException e) {
			throw new Exception("PageBase.html(): " + e.getMessage());
		}
	}

	// ********************************************
	// methods to use in the derived plugin class:
	// ********************************************
	// file log
	public void log(String strText, int nLogLevel) {
		plugin.log(strText, nLogLevel);
	}

	public void log(String strText) {
		plugin.log(strText);
	}

	// methods to add this page to the plugins' menu (fproxy)
	protected void addPageToMenu(String strMenuTitle, String strMenuTooltip) {
		try {

			plugin.pluginContext.pluginRespirator.getToadletContainer().unregister(this);
			plugin.pluginContext.pluginRespirator.getToadletContainer().register(this, plugin.getCategory(),
					this.path(), true, strMenuTitle, strMenuTooltip, bFullAccessHostsOnly, null);
			log("page '" + strPageName + "' added to menu");

		} catch (Exception e) {
			log("PageBase.addPageToMenu(): " + e.getMessage(), 1);
		}
	}

	// methods to build the page
	protected void addBox(String title, String htmlBody, String id) {
		try {

			InfoboxNode box = plugin.pagemaker.getInfobox(title);
			if (id != null) {
				box.outer.addAttribute("id", id);
			}
			htmlBody = htmlBody.replaceAll("'", "\"");
			box.content.addChild("%", htmlBody);
			vBoxes.add(box.outer);

		} catch (Exception e) {
			log("PluginBase.addBox(): " + e.getMessage(), 1);
		}
	}

	protected String html(String name, String formPassword) throws Exception {
		try (InputStream stream =
						getClass()
							 .getResourceAsStream("/resources/templates/" + name + ".html")) {

			ByteArrayOutputStream content = new ByteArrayOutputStream();
			int len;
			byte[] contentBytes = new byte[1024];
			while ((len = stream.read(contentBytes)) != -1) {
				content.write(contentBytes, 0, len);
			}

			return content.toString(UTF_8.name()).replaceAll("\\$\\{formPassword}", formPassword);
		} catch (IOException e) {
			throw new Exception("PageBase.html(): " + e.getMessage());
		}
	}

	// methods to make the page refresh
	protected void setRefresh(int nPeriod, String strTarget) {
		this.nRefreshPeriod = nPeriod;
		this.strRefreshTarget = strTarget;
	}

	protected void setRefresh(int nPeriod) {
		setRefresh(nPeriod, null);
	}

	// methods to handle http requests (both get and post)
	abstract protected void handleRequest();

	protected String getQuery() throws Exception {
		try {

			return uri.getQuery();

		} catch (Exception e) {
			throw new Exception("PageBase.getQuery(): " + e.getMessage());
		}
	}

	protected HTTPRequest getRequest() {
		return httpRequest;
	}

	protected String getParam(String strKey) throws Exception {
		try {

			if (httpRequest.getMethod().toUpperCase().equals("GET") && !httpRequest.getParam(strKey).equals("")) {
				return httpRequest.getParam(strKey);
			} else if (httpRequest.getMethod().toUpperCase().equals("POST") && httpRequest.getPart(strKey) != null) {
				byte[] aContent = new byte[(int) httpRequest.getPart(strKey).size()];
				httpRequest.getPart(strKey).getInputStream().read(aContent);
				return new String(aContent, UTF_8);
			} else {
				return null;
			}

		} catch (IOException e) {
			throw new Exception("PageBase.getParam(): " + e.getMessage());
		}
	}

	protected int getIntParam(String cKey) throws Exception {
		try {

			return Integer.valueOf(getParam(cKey));

		} catch (Exception e) {
			throw new Exception("PageBase.getIntParam(): " + e.getMessage());
		}
	}

	// methods to handle fcp messages
	protected Message getMessage(String cId, String cMessageType) throws Exception {
		try {

			Message message = mMessages.get(cId);
			if (message != null && !message.getName().equals(cMessageType)) {
				message = null;
			}
			if (message != null) {
				mMessages.remove(cId);
				strRedirectURI = mRedirectURIs.get(cId);
				mRedirectURIs.remove(cId);
			}
			return message;                 // returns null if no message

		} catch (Exception e) {
			throw new Exception("PageBase.getMessage(): " + e.getMessage());
		}
	}

	protected String getRedirectURI() {
		return strRedirectURI;
	}

	protected String[] getSSKKeypair(String cId) {
		try {

			Message message = getMessage(cId, "SSKKeypair");
			if (message != null) {
				return new String[]{message.get("InsertURI"), message.get("RequestURI")};
			} else {
				return null;
			}

		} catch (Exception e) {
			log("PageBase.getSSKKeypair(): " + e.getMessage(), 1);
			return null;
		}
	}

	// methods to set and get persistent properties
	public void saveProp() {
		plugin.saveProp();
	}

	public void setProp(String cKey, String cValue) throws Exception {
		plugin.setProp(cKey, cValue);
	}

	public String getProp(String cKey) throws Exception {
		return plugin.getProp(cKey);
	}

	public void setIntProp(String cKey, int nValue) throws Exception {
		plugin.setIntProp(cKey, nValue);
	}

	public int getIntProp(String cKey) throws Exception {
		return plugin.getIntProp(cKey);
	}

	public void removeProp(String cKey) {
		plugin.removeProp(cKey);
	}

	// method to allow access to this page for full-access-hosts only
	public void restrictToFullAccessHosts(boolean bRestrict) {
		bFullAccessHostsOnly = bRestrict;
	}

}
