/*
 * Copyright (c) 2008, AIST, the University of Tokyo and General Robotix Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * Contributors:
 * General Robotix Inc.
 * National Institute of Advanced Industrial Science and Technology (AIST)
 */
/*
 *  GrxPluginManager.java
 *
 *  Copyright (C) 2007 GeneralRobotix, Inc.
 *  All Rights Reserved
 *
 *  @author Yuichiro Kawasumi (General Robotix, Inc.)
 */

package com.generalrobotix.ui;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.omg.PortableServer.POA;

import org.w3c.dom.Element;

import com.generalrobotix.ui.grxui.Activator;
import com.generalrobotix.ui.grxui.GrxUIPerspectiveFactory;

import com.generalrobotix.ui.util.GrxCorbaUtil;
import com.generalrobotix.ui.util.GrxDebugUtil;
import com.generalrobotix.ui.util.GrxPluginLoader;
import com.generalrobotix.ui.util.GrxXmlUtil;
import com.generalrobotix.ui.util.OrderedHashMap;
import com.generalrobotix.ui.util.SynchronizedAccessor;
import com.generalrobotix.ui.view.GrxProcessManagerView;
import com.generalrobotix.ui.item.GrxModeInfoItem;
import com.generalrobotix.ui.item.GrxProjectItem;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * @brief プラグイン管理クラス
 * GrxUIの核になるクラス。プラグインのロード等の、初期化を実行する。
 * また、タイマーを使ったプラグイン間の同期を行う。
 * プラグインとそのアイテムのマップ（#pluginMap_）、プラグインとその情報のマップ（#pinfoMap_）などを持つ。
 * 各種プラグインはこのクラスへの参照を持ち、必要に応じてこのクラスから情報を取得する事ができる。
 * @see GrxUIFrame
 * @see GrxPluginLoader
 * @see GrxProjectItem
 */
public class GrxPluginManager
{
	// project
	private GrxProjectItem  rcProject_;
	private GrxProjectItem  currentProject_;
	private GrxModeInfoItem currentMode_;
	private GrxBaseItem focusedItem_=null;

	// for managing items
	public  GrxPluginLoader pluginLoader_;
	public HashMap<Class<? extends GrxBasePlugin>, OrderedHashMap> pluginMap_ =
		new HashMap<Class<? extends GrxBasePlugin>, OrderedHashMap>(); // プラグインとその生成したアイテムのマップ
	private List<GrxBaseItem> selectedItemList_ = new ArrayList<GrxBaseItem>();//ダブルクリックによるトグルステータス
	private List<GrxBaseView> selectedViewList_ = new ArrayList<GrxBaseView>();
	private boolean bItemSelectionChanged_ = false;
	private boolean bItemListChanged_ = false;
	private boolean bfocusedItemChanged_ = false;
	private boolean bItemPropertyChanged_ = false;
    private String homePath_;
	private Map<Class<? extends GrxBasePlugin>, PluginInfo> pinfoMap_ =
		new HashMap<Class<? extends GrxBasePlugin>, PluginInfo>();

	// for cyclic execution
	private int delay_ = DEFAULT_INTERVAL;// [msec]
	private long prevTime_ = 0; // [msec]
	public  double min, max, now; // [msec]
	private static final int  DEFAULT_INTERVAL = 100; // [msec]

	// for CORBA
	public POA poa_;
	public org.omg.CORBA.ORB orb_;

	// 初期化に成功したかどうか
	public boolean initSucceed=false;

	// アイテムのアップデート確認スレッド実行用
	Display display;


	/**
	 * @brief GrxPluginManagerのコンストラクタ.
	 * まず、プラグインローダ（GrxPluginLoader）のインスタンス#pluginLoader_を作成する。<br>
	 * そして最初に、GrxModeInfoItemをロードする。これは「モード」を管理するアイテムプラグインである。<br>
	 * モードの読み込みは#start()関数内で行われるのでそちらを参照。<br>
	 * 次にプロジェクトを司るアイテム（GrxProjectItem）を作成する。その際、設定ファイルとしてgrxuirc.xmlを指定する。<br>
	 * Javaのプロパティ「PROJECT」によりデフォルトのプロジェクトが指定されている場合、それをロードする。（なければ生成する。）<br>
	 * 最後に、アイテムの更新を行うためタイマーを生成し、定期的に#_updateItemSelection()を実行する。<br>
	 * その結果アイテムに変更があると、現在のプロジェクトから作ったツリー(#treeModel_)に対してreload() を実行する。
	 * @see GrxPluginLoader
	 * @see GrxModeInfoItem
	 * @see GrxPluginManager#start()
	 * @see GrxProjectItem
	 */
	public GrxPluginManager()
	{
		GrxDebugUtil.println("[PM] GrxPluginManager created");

		String dir = System.getenv("ROBOT_DIR");
		if (dir != null && new File(dir).isDirectory())
			homePath_ = dir+File.separator;
		else
			// TODO:デフォルトのディレクトリの検討。現在暫定でユーザホーム
			// 前はuser.dirになっていたが、これだとEclipse実行ファイルの位置になってしまいLinux等では権限エラーになったりする。
			//homePath_ = System.getProperty("user.dir","")+File.separator;
			homePath_ = System.getProperty( "user.home", "" )+File.separator;

		System.out.println("[PM] WORKSPACE PATH="+ ResourcesPlugin.getWorkspace().getRoot().getLocation() );

		// TODO: プラグインローダに、プラグインがおいてあるフォルダを指定する方法を検討
		// 1.そもそもプラグイン管理をEclipseでやらせる
		// 2.Eclipseの機能を使ってプラグインのディレクトリを持ってきてもらう
		// 3.とりあえずGrxUIプラグイン自身をロードしたクラスローダを渡しておく <- いまこれ
		pluginLoader_ = new GrxPluginLoader("plugin", GrxPluginManager.class.getClassLoader());
		registerPlugin(GrxModeInfoItem.class);

		// load default plugin settings
		// 移植前はhomePath_においてある事を期待していたが、プラグインに含めるようにした。
		// homePath_にgrxuirc.xmlがあるかチェックし、なければプラグインフォルダからデフォルトをコピーする。
		rcProject_ = new GrxProjectItem("grxuirc", this);
//	    File rcFile = new File( Activator.getPath() + "/grxuirc.xml");
		// Windows と Linuxで使い分ける。
		File rcFile;
		System.out.println("os.name = "+System.getProperty("os.name"));
		if( System.getProperty("os.name").equals("Linux")||System.getProperty("os.name").equals("Mac OS X"))
		{
			rcFile = new File( homePath_ + "grxuirc.xml");
		}
		else
		{
			rcFile = new File( System.getenv("TEMP") + File.separator + "grxuirc.xml" );
		}
		System.out.println("rcFile="+rcFile);
		if( !rcFile.exists())
	    {
	    	// File copy
	    	try{
	    		InputStream in = new FileInputStream( Activator.getPath() + File.separator + "grxuirc.xml");
	    		OutputStream out = new FileOutputStream( rcFile.toString() );
	    		byte[] buf = new byte[1024];
	    		int len ;
	    		while(( len = in.read( buf )) > 0 )
	    		{
	    			out.write( buf , 0 , len );
	    		}
	    		in.close();
	    		out.close();
	    	}catch ( IOException e) {
	    	    e.printStackTrace();
	    	}
	    }
	    if (!rcProject_.load( rcFile )) {
			MessageDialog.openError(null,"Can't Start GrxUI", "Can't find grxuirc.xml. on "+rcFile );
			//TODO: プラグインを閉じる方法があればそれを採用する?
			//System.exit(0);
			return;
		}
	    currentProject_ = new GrxProjectItem("newproject", this);

		// load default project
		String defaultProject = System.getProperty("PROJECT", null);
		if (defaultProject == null ||
				!currentProject_.load(new File(GrxXmlUtil.expandEnvVal(defaultProject))))
			currentProject_.create();
		//root_.setUserObject(currentProject_);

		display = Display.getCurrent();
		Runnable runnable = new Runnable() {
			public void run() {
				if ( bItemSelectionChanged_ ) _updateItemSelection();
				updateViewList();
		        if ( bItemListChanged_ ) _notifyItemListChanged();
		        if ( bfocusedItemChanged_ ) _notifyFocusedItemChanged();
		        if ( bItemPropertyChanged_ ) _notifyItemPropertyChanged();
		        if( isPerspectiveVisible() )
					_control();
				if ( ! display.isDisposed() )
					display.timerExec(delay_, this);
			}
		};
		if ( ! display.isDisposed() )
			display.timerExec( delay_, runnable );

		// TODO: 「grxuirc.xmlが見つからないとexit()」の代替だが、もっとスマートな方法を考えよう
		initSucceed = true;
	}

	/**
	 * @brief set focused item
	 * @param item focused item
	 */
	public void focusedItem(GrxBaseItem item){
		if (focusedItem_ != item){
			if (focusedItem_ != null) focusedItem_.setFocused(false);
			focusedItem_ = item;
			focusedItem_.setFocused(true);
			bfocusedItemChanged_ = true;
		}
	}
	
	/**
	 * @brief get current focused item
	 * @return item
	 */
	public GrxBaseItem focusedItem() {
		return focusedItem_;
	}
	
	/**
	 * @brief check if GrxUI perspective is visible or not
	 * @return true if visible, false otherwise
	 */
	private boolean isPerspectiveVisible(){
  		IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        if( window == null )
        	return false;
       	IWorkbenchPage page = window.getActivePage();
       	if( page==null )
       		return false;
   		IPerspectiveDescriptor pers = page.getPerspective();
   		if( ! pers.getId().equals( GrxUIPerspectiveFactory.ID ) )
   			return false;
		return true;
	}

	/**
	 * @brief update list of selected items and notify all views
	 */
	private void _updateItemSelection() {
		//GrxDebugUtil.println("[PM THREAD] update Item Selections");

		bItemSelectionChanged_ = false;
		selectedItemList_.clear();
		Collection<OrderedHashMap> oMaps = pluginMap_.values();
		for( OrderedHashMap om : oMaps ) {
			for( Object o : om.values() ){
				if (o instanceof GrxBaseItem && ((GrxBaseItem) o).isSelected())
					selectedItemList_.add((GrxBaseItem) o);
			}
		}
		
		GrxDebugUtil.println("[PM THREAD] selectedItems = "+selectedItemList_.toString());

		updateViewList();
		for (int i=0; i<selectedViewList_.size(); i++) {
			GrxBaseView view = selectedViewList_.get(i);
			try {
				view.itemSelectionChanged(selectedItemList_);
			} catch (Exception e1) {
				GrxDebugUtil.printErr("Control thread (itemSelectionChanged):"
						+ view.getName() + " got exception.", e1);
			}
		}
	}

	/**
	 * @brief notify all views that list of items is changed
	 */
	private void _notifyItemListChanged() {
		for (int i=0; i<selectedViewList_.size(); i++){
			GrxBaseView v = (GrxBaseView)selectedViewList_.get(i);
			v.itemListChanged();
		}
		bItemListChanged_ = false;
	}

	/**
	 * @brief notify all views that property of current item is changed
	 */
	private void _notifyItemPropertyChanged() {
		//System.out.println("GrxPluginManager.propertyChanged()");
		for (int i=0; i<selectedViewList_.size(); i++){
			GrxBaseView v = (GrxBaseView)selectedViewList_.get(i);
			v.propertyChanged();
		}
		bItemPropertyChanged_ = false;
	}

	/**
	 * @brief notify this manager that property of current item is changed
	 */
	public void itemPropertyChanged() {
		bItemPropertyChanged_ = true;
	}


	private void _notifyFocusedItemChanged() {
		//System.out.println("GrxPluginManager._notifyFocusedItemChanged()");
		for (int i=0; i<selectedViewList_.size(); i++){
			GrxBaseView v = (GrxBaseView)selectedViewList_.get(i);
			v.focusedItemChanged(focusedItem_);
		}
		bfocusedItemChanged_ = false;
	}
	/**
	 * @brief update list of views
	 */
	private void updateViewList(){
		selectedViewList_.clear();

  		IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        if( window == null )
        	return;
        IWorkbenchPage page = window.getActivePage();
        if( page==null ){
        	return;
        }
        IPerspectiveDescriptor pers = page.getPerspective();
        // GrxUIパースペクティブが表示されているか？
        if( ! pers.getId().equals( GrxUIPerspectiveFactory.ID ) ){
        	return;
        }

       	for( IViewReference i : page.getViewReferences() ) {
       		// 未初期化のビューは初期化する
       		IViewPart v = i.getView( true );
       		if( v!=null && GrxBaseViewPart.class.isAssignableFrom( v.getClass() ) ){
        		GrxBaseView view = ((GrxBaseViewPart)v).getGrxBaseView();
        		selectedViewList_.add(view);
       		}
       	}
	}

	/**
	 * @brief call control of all views
	 */
	private void _control() {
		long t = System.currentTimeMillis();
		now = t - prevTime_;
		prevTime_ = t;
		max = Math.max(max, now);
		min = Math.min(min, now);

		updateViewList();
		for (int i = 0; i < selectedViewList_.size(); i++) {
       		GrxBaseView view = selectedViewList_.get(i);
			try {
				switch (view.view_state_) {
				case GrxBaseView.GRX_VIEW_SETUP:
					if (view.setup(selectedItemList_))
						view.view_state_ = GrxBaseView.GRX_VIEW_ACTIVE;
					break;

				case GrxBaseView.GRX_VIEW_ACTIVE:
					long prev = System.currentTimeMillis();
					view.control(selectedItemList_);
					t = System.currentTimeMillis();
					view.now = t - prev;
					if (view.max < view.now)
						view.max = view.now;
					if (view.min > view.now)
						view.min = view.now;
					break;

				case GrxBaseView.GRX_VIEW_CLEANUP:
					if (view.cleanup(selectedItemList_))
						view.view_state_ = GrxBaseView.GRX_VIEW_SLEEP;
					break;

				case GrxBaseView.GRX_VIEW_SLEEP:
					break;
				}
			} catch (final Exception e) {
				GrxDebugUtil.printErr("Control thread :"
						+ view.getName() + " got exception.", e);
			}
		}
	}

	/**
	 * 全体の処理の開始.
	 * 最初に、CORBAのスレッドを開始する。<br>
	 * 次にデフォルトのモードをJavaのプロパティ「MODE」から決めてsetMode()を実行する。<br>
	 * 「モード」はロードすべきプラグインとその配置のプリセットであり、設定ファイルgrxuirc.xmlにて指定されている。<br>
	 * デフォルトのモードが指定されていない場合、最初に現れたモードをデフォルトとして、ダイアログを出してユーザに選択を求める。
	 */
	public void start() {
		System.out.println("[PM] START GrxPluginManager");
		new Thread() {
			public void run() {
				try {
					poa_ = GrxCorbaUtil.getRootPOA();
					poa_.the_POAManager().activate();
					GrxDebugUtil.println("Corba Server Ready.");
					orb_ = GrxCorbaUtil.getORB();
					orb_.run();
					orb_.destroy();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}.start();

		//frame_.setVisible(true);

		String defaultMode = System.getProperty("MODE", "");
		GrxModeInfoItem mode = (GrxModeInfoItem)getItem(GrxModeInfoItem.class, defaultMode);
		GrxDebugUtil.println("[PM] current mode="+mode);

		Map<?, ?> m = pluginMap_.get(GrxModeInfoItem.class);
		GrxModeInfoItem[] modes = (GrxModeInfoItem[])m.values().toArray(new GrxModeInfoItem[0]);

		System.out.println("[PM] try to setMode");

		try {
			if (mode == null) {
				int ans = 0;
 				if (modes.length > 1) {
					String[] modeInfoNames = new String[modes.length];
					for( int i=0; i<modes.length; i++ )
						modeInfoNames[i] = modes[i].getName();
					MessageDialog dlg = new MessageDialog( null, "Select Mode", null, "Select Initial Mode.", MessageDialog.NONE, modeInfoNames, 0 );
					ans = dlg.open();
				}
				mode = modes[ans];
			}
			setMode(mode);
//			frame_.updateModeButtons(modes, currentMode_);

		} catch (Exception e) {
			GrxDebugUtil.printErr("GrxPluginManager:",e);
		}
	}

	/**
	 * モードを設定する.
	 * アクティブなプラグインのリストの更新と、画面の更新を行う。
	 */
	void setMode(GrxModeInfoItem mode) {
		System.out.println("[PM] setMode to "+mode);

		if (currentMode_ == mode)
			return;

		// prepare change mode
		for (int i=0; i<selectedViewList_.size(); i++) {
			GrxBaseView view = selectedViewList_.get(i);
			view.stop();
			while (!view.isSleeping()) {
				try {
					Thread.sleep(getDelay());
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		//timer_.stop();
		clearItemSelection();

		// update active plugin and create view
		setSelectedItem(mode, true);
		currentMode_ = mode;
		currentMode_.restoreProperties();

		now = max = min = 0.0;

		//timer_.start();

		currentProject_.restoreProject();
	}
	
	/**
	 * @brief get current mode
	 * @return current mode
	 */
	public GrxModeInfoItem getMode(){
		return currentMode_;
	}

	public void setDelay(int msec) {
		delay_ = msec;
		//timer_.setDelay(msec);
	}
	public int getDelay() {
		return delay_;
	}

	public Class<? extends GrxBasePlugin> registerPlugin(String className) {
		Class<?> cls = pluginLoader_.loadClass(className);
		return registerPlugin((Class<? extends GrxBasePlugin>) cls);
	}

	/**
	 * エレメントの値をPluginInfoに反映させるためのメソッド
	 * @param el
	 * @return
	 */
	public Class<?> registerPlugin(Element el) {
		Class<?> cls = pluginLoader_.loadClass( el.getAttribute("class") );
		Class<? extends GrxBasePlugin> ret = registerPlugin((Class<? extends GrxBasePlugin>) cls);
		if( ret != null){
			PluginInfo pi = pinfoMap_.get(ret);
			pi.visible = GrxXmlUtil.getBoolean(el, PluginInfo.VISIBLE, true);
			pinfoMap_.put(ret, pi);
		}
		return ret;
	}

	/**
	 * プラグインの登録関数。
	 * 該当するプラグインが無い場合、nullを返す。
	 * @return プラグインへの参照（GrxBasePluginにキャストして使う）、該当無しの場合はnull
	 */
	public Class<? extends GrxBasePlugin> registerPlugin(Class<? extends GrxBasePlugin> cls) {
		if (cls != null && GrxBasePlugin.class.isAssignableFrom(cls)) {
		    if (pluginMap_.get(cls) == null) {
				GrxDebugUtil.println( "[PM] register " + cls.getName() );

		    	pluginMap_.put(cls, new OrderedHashMap());

		    	PluginInfo pi = new PluginInfo();
                pi.title = (String)GrxBasePlugin.getField(cls, "TITLE", cls.getSimpleName());
				pi.lastDir = new File(homePath_+(String)GrxBasePlugin.getField(cls, "DEFAULT_DIR", ""));
				String ext = (String)GrxBasePlugin.getField(cls, "FILE_EXTENSION", null);
				if (ext != null)
					pi.filter = "*."+ext;//GrxGuiUtil.createFileFilter(ext);
			    pinfoMap_.put(cls, pi);
		    }

		    return cls;
		}
		GrxDebugUtil.println("[PM] 該当クラスなし。"+cls.toString()+" register fault.");
	    return null;
	}

	/**
	 * @brief アイテムの作成.
	 * 指定したアイテムプラグインに、指定したアイテム名で新しいアイテムを作る。
	 * @param cls プラグインのクラス.　GrxXXXItem.classのように指定する。
	 * @param name 新しく作成するアイテムの名前。nullの場合アイテムプラグインの指定するデフォルトが使用される。
	 * @return アイテムプラグイン。該当するプラグインが無い場合はnullを返す
	 */
	public GrxBaseItem createItem(Class<? extends GrxBaseItem> cls, String name) {
		System.out.println("[PM]@createItem "+name+"("+cls+")");
		if (name == null) {
			String baseName = "new" + getItemTitle(cls);
			baseName = baseName.toLowerCase().replaceAll(" ", "");
			for (int i = 0;; i++) {
				name = baseName + i;
				if (getItem(cls, name) == null)
					break;
			}
		}
		GrxBaseItem item = (GrxBaseItem) createPlugin(cls, name);
		GrxDebugUtil.println("[PM]@createItem createPlugin return "+item);
		if (item != null) {
			item.create();
			setSelectedItem(item, true);
			_addItemNode(item);
		}
		return item;
	}

	/**
	 * @brief
	 * @param cls
	 * @param name
	 * @param url
	 * @return
	 */
	public GrxBaseItem loadItem(Class<? extends GrxBaseItem> cls, String name, String url) {
		String _url = GrxXmlUtil.expandEnvVal(url);
		if (_url == null)
			return null;

		File f = null;
		try {
			URL u = new URL(_url);
			f = new File(u.getFile());
		} catch (Exception e) {
			GrxDebugUtil.printErr("loadItem("+url+") is not URL format\n", e);
	        f = new File(_url);
		}

		if (!f.isFile())
			return null;

		if (name == null)
			name = f.getName().split("[.]")[0];

		GrxBaseItem item = (GrxBaseItem) createPlugin(cls, name);
		if (item != null) {
			if (item.load(f)) {
				item.setURL(url);
				setSelectedItem(item, true);
				_addItemNode(item);
			} else {
				removeItem(item);
			}
		}

		return item;
	}

    /**
     * @brief
     * @param cls
     * @return
     */
    public GrxBaseItem pasteItem(Class<? extends GrxBaseItem> cls, GrxBaseItem item) {
/*    	
		try {
			HashMap<String, GrxBasePlugin> map = pluginMap_.get(cls);
			GrxBasePlugin plugin = map.get(item.getName());

			plugin = pluginLoader_.createPlugin(cls, name, this);
			
			map.put(name, plugin);
			return plugin;

		} catch (Exception e) {
			showExceptionTrace("Couldn't load Class:" + cls.getName(), e);
		}
*/        
        return item;
    }


	/**
	 * @brief
	 * @param cls
	 * @param name
	 * @return
	 */
	public GrxBaseView createView(Class<? extends GrxBaseView> cls, String name) {
        if (name == null)
            name = pinfoMap_.get(cls).title;
        return (GrxBaseView) createPlugin(cls, name);
	}

	/**
	 * @brief
	 * @param cls
	 * @param name
	 * @return
	 */
	private GrxBasePlugin createPlugin(Class<? extends GrxBasePlugin> cls, String name) {
		if (registerPlugin(cls) == null) {
			GrxDebugUtil.println("[PM]@createPlugin registerPlugin failed");
			return null;
		}
		try {
			GrxBasePlugin plugin = pluginLoader_.createPlugin(cls, name, this);
			if (registerPluginInstance(plugin)){
				return plugin;
			}
			return plugin;
		} catch (Exception e) {
			showExceptionTrace("Couldn't load Class:" + cls.getName(), e);
		}
		return null;
	}

	/**
	 * @brief register instance of plugin to this manager
	 * @param instance instance of plugin
	 * @return true if registered successfully, false otherwise
	 */
	public boolean registerPluginInstance(GrxBasePlugin instance){
		HashMap<String, GrxBasePlugin> map = pluginMap_.get(instance.getClass());
		if (map.get(instance.getName()) != null) {
			GrxDebugUtil.println("[PM]@createPlugin Plugin Instance already registered.");
			return false;
		}
		map.put(instance.getName(), instance);
		return true;
	}
	
	/**
	 * @brief
	 * @param m
	 * @param e
	 */
	private void showExceptionTrace(String m, Exception e) {
		GrxDebugUtil.printErr(m, e);

		Throwable cause = e.getCause();
		StackTraceElement[] trace = null;
		String msg = m + "\n\n";
		if (cause != null) {
			msg = cause.toString() + "\n\n";
			trace = cause.getStackTrace();
		} else {
			trace = e.getStackTrace();
		}

		for (int i = 0; i < trace.length; i++) {
			msg += "at " + trace[i].getClassName() + "."
					+ trace[i].getMethodName() + "(";

			if (trace[i].isNativeMethod()) {
				msg += "(Native Method)\n";
			} else if (trace[i].getFileName() == null) {
				msg += "(No source code)\n";
			} else {
				msg += trace[i].getFileName() + ":" + trace[i].getLineNumber() + ")\n";
			}
		}

		//JOptionPane.showMessageDialog(getFrame(), msg, "Exception Occered",
		//		JOptionPane.WARNING_MESSAGE, ROBOT_ICON);
		MessageDialog.openWarning( null, "Exception Occered", msg);
	}

	/**
	 * @brief
	 * @param item
	 */
	private void _addItemNode(GrxBaseItem item) {
		if (item.isSelected()) bItemSelectionChanged_ = true;
		bItemListChanged_ = true;
	}

	/**
	 * @brief remove item
	 * @param item item to be removed
	 */
	public void removeItem(GrxBaseItem item) {
		Map<?, ?> m = pluginMap_.get(item.getClass());
		if (m != null) {
			setSelectedItem(item, false);
			m.remove(item.getName());
			reselectItems();
		}
	}

	/**
	 * @brief remove all items which are instances of specified class
	 * @param cls class
	 */
	public void removeItems(Class<? extends GrxBaseItem> cls) {
		Map<?, ?> m = pluginMap_.get(cls);
		GrxBaseItem[] items = m.values().toArray(new GrxBaseItem[0]);
		for (int i = 0; i < items.length; i++)
			items[i].delete();
	}

	/**
	 * @brief remove all items which are instances of active classes in the current mode
	 */
	public void removeAllItems() {
		if (currentMode_ == null)
			return;
		for (int i = 0; i < currentMode_.activeItemClassList_.size(); i++)
			removeItems(currentMode_.activeItemClassList_.get(i));
	}

	/**
	 * @brief rename plugin. If new name is already used, name is not changed.
	 * @param item plugin to be renamed
	 * @param newName new name
	 * @return true renamed successfully, false otherwise
	 */
	public boolean renamePlugin(GrxBasePlugin item, String newName) {
		OrderedHashMap m = pluginMap_.get(item.getClass());
		if (m == null){
			System.out.println("map for "+item.getClass()+" doesn't exist in pluginMap_");
			return false;
		}
        if (m.get(newName) == null) {
        	if (!newName.equals(item.getName())){
        		m.remove(item.getName());
        		m.put(newName, item);
        		item.setName(newName);
        		bItemListChanged_ = true;
        	}
        	return true;
        }else{
        	System.out.println("GrxPluginManager.renamePlugin() : "+newName+" is already used");
        }
        return false;
	}

	/**
	 * @brief
	 */
	public void setVisibleItem() {
		ArrayList<Class<? extends GrxBaseItem>> cList = currentMode_.activeItemClassList_;
		for (int i = 0; i < cList.size(); i++) {
			Iterator it = pluginMap_.get(cList.get(i)).values().iterator();
			while (it.hasNext())
				_addItemNode((GrxBaseItem) it.next());
		}
		bItemListChanged_ = true;
	}

	/**
	 * @brief
	 * @return
	 */
	public ArrayList<GrxBaseItem> getActiveItemList() {
		ArrayList<Class<? extends GrxBaseItem>> cList = currentMode_.activeItemClassList_;
		ArrayList<GrxBaseItem> iList = new ArrayList<GrxBaseItem>();
		for (int i = 0; i < cList.size(); i++) {
			Iterator it =  pluginMap_.get(cList.get(i)).values().iterator();
			while (it.hasNext()) {
                GrxBaseItem item = (GrxBaseItem)it.next();
				iList.add(item);
            }
		}
		return iList;
	}

	/**
	 * @brief
	 * @param cls
	 * @param name
	 * @return
	 */
	public GrxBaseItem getItem(Class<? extends GrxBaseItem> cls, String name) {
		Iterator it = pluginMap_.get(cls).values().iterator();
		while (it.hasNext()) {
			GrxBaseItem item = (GrxBaseItem)it.next();
			if (item.toString().equals(name) || name == null) {
				GrxDebugUtil.println("[PM] getItem success "+item);
				return item;
			}
		}
		GrxDebugUtil.println("[PM] fault getItem "+cls.getName()+":"+name);
		return null;
	}

	/**
	 * @brief
	 * @param name
	 * @return
	 */
	public GrxBaseItem getItem(String name) {
		Iterator<OrderedHashMap> it = pluginMap_.values().iterator();
		while (it.hasNext()) {
			HashMap m = (HashMap) it.next();
			Object o = m.get(name);
			if (o != null && o instanceof GrxBaseItem)
				return (GrxBaseItem) o;
		}
		return null;
	}

	/**
	 * @brief
	 * @param cls
	 * @return
	 */
	public Map<?, ?> getItemMap(Class<? extends GrxBaseItem> cls) {
		return pluginMap_.get(cls);
	}

	/**
	 * @brief
	 * @param cls
	 * @param name
	 * @return
	 */
	public GrxBaseItem getSelectedItem(Class<? extends GrxBaseItem> cls, String name) {
		for (int i = 0; i < selectedItemList_.size(); i++) {
			GrxBaseItem item = selectedItemList_.get(i);
			if (cls.isAssignableFrom(item.getClass()) && (name == null || name.equals(item.getName()))) {
				System.out.println("[PM]@getSelectedItem get "+item.getName()+"("+cls.getName()+")");
				return item;
			}
		}
		System.out.println("[PM]@getSelectedItem "+cls.getName()+":"+name+" NOT FOUND.");
		return null;
	}

	/**
	 * @brief
	 * @param cls
	 * @return
	 */
	public List<GrxBaseItem> getSelectedItemList(Class<? extends GrxBaseItem> cls) {
		ArrayList<GrxBaseItem> list = new ArrayList<GrxBaseItem>();
		for (int i = 0; i < selectedItemList_.size(); i++) {
			GrxBaseItem item = selectedItemList_.get(i);
			if (cls.isInstance(item))
				list.add(item);
		}
		return list;
	}

	/**
	 * @brief
	 * @return
	 */
	public List<GrxBaseView> getActiveViewList() {
		return selectedViewList_;
	}

	/**
	 * @brief
	 * @param cls
	 * @return
	 */
	public GrxBaseView getView(Class<? extends GrxBaseView> cls) {
		updateViewList();
		for( GrxBaseView v : selectedViewList_ )
			if( v.getClass() == cls )
				return v;

		return null;
	}

	public synchronized GrxBaseView getView(String name) {
		updateViewList();
		for (GrxBaseView v : selectedViewList_){
			if (v.getName().equals(name)){
				return v;
			}
		}
		return null;
	}

	/**
	 * @brief select/unselect item
	 * @param item item to be selected/unselected
	 * @param select true to select, false to unselect
	 */
	public void setSelectedItem(GrxBaseItem item, boolean select) {
		if (item == null)
			return;

		//GrxDebugUtil.println("[PM]@setSelectedItem "+item.getName()+" selection to "+select );

		if (select ^ item.isSelected()) reselectItems();

		if (select && item.isExclusive()) {
			for( GrxBaseItem i: (Collection<GrxBaseItem>)getItemMap(item.getClass()).values() ){
				if( i != item) {
					i.setSelected(false);
				}
			}
		}

		//GrxDebugUtil.println("[PM]@setSelectedItem "+item.getName()+" to "+select+". and now changed? "+isItemSelectionChanged_ );

		item.setSelected(select);
	}

	/**
	 * @brief set flag to update item selection list
	 */
	public void reselectItems() {
		bItemSelectionChanged_ = true;
	}

	/**
	 * @brief unselect all items
	 */
	public void clearItemSelection() {
		Iterator<OrderedHashMap> i = pluginMap_.values().iterator();
		while (i.hasNext()) {
			Iterator j = (i.next()).values().iterator();
			while (j.hasNext()) {
				GrxBasePlugin p = (GrxBasePlugin)j.next();
				if (p instanceof GrxBaseItem)
					setSelectedItem((GrxBaseItem)p, false);
			}
		}
	}

	/**
	 * @brief get title of item class
	 * @param cls item class
	 * @return title
	 */
	public String getItemTitle(Class<? extends GrxBasePlugin> cls) {
        return pinfoMap_.get(cls).title;
	}

	/**
	 * @brief check an item class is visible or not
	 * @param cls item class
	 * @return true if visible, false otherwise
	 */
	public boolean isItemVisible(Class<? extends GrxBasePlugin> cls) {
        return pinfoMap_.get(cls).visible;
	}

	/**
	 * @brief information of plugin class
	 */
	private class PluginInfo {
		static final String VISIBLE="visible";
		String title;
		File   lastDir;
		String filter;
		Vector<Action> menu;
		boolean visible;
	}

	/**
	 * @brief
	 * @param cls
	 * @return
	 */
	public Vector<Action> getItemMenu(final Class<? extends GrxBaseItem> cls) {
		final PluginInfo pi = pinfoMap_.get(cls);
		Vector<Action> menu = pi.menu;
		if (menu != null){
		    dynamicChangeMenu(cls, menu);
			return menu;
		}
		menu = pi.menu = new Vector<Action>();


		// menu item : create
		Action create = new Action(){
			public String getText(){
				return "create";
			}
			public void run(){
				createItem(cls, null);
			}
		};
		menu.add(create);

		// menu item : load
		Action load = new Action(){
			public String getText(){
				return "load";
			}
			public void run(){
				IWorkbench workbench = PlatformUI.getWorkbench();
		        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
				FileDialog fdlg = new FileDialog( window.getShell(), SWT.OPEN);
				String[] fe = { pi.filter};
				fdlg.setFilterExtensions( fe );
				String fPath = fdlg.open();
				if( fPath != null ) {
					File f = new File(fPath);
					loadItem(cls, null, f.getAbsolutePath() );
					pi.lastDir = f.getParentFile();
				}
			}
		};
		menu.add(load);

		// menu item : clear
		Action clear = new Action(){
			public String getText(){
				return "clear";
			}
			public void run(){
				if( MessageDialog.openConfirm(null,"remove items",
						"Remove all the items : "+GrxPluginManager.this.getItemTitle(cls)+" ?") )
					removeItems(cls);
			}
		};
		menu.add(clear);


		try {
			Method m = cls.getMethod("create", (Class[]) null);
			Class<?> c = m.getDeclaringClass();
			create.setEnabled(!(c == GrxBaseItem.class));

			m = cls.getMethod("load", File.class);
			c = m.getDeclaringClass();
			load.setEnabled(!(c == GrxBaseItem.class));
			m = cls.getMethod("paste", String.class);
			c = m.getDeclaringClass();
			if( c != GrxBaseItem.class ){
	            Action paste = new Action(){
	                public String getText(){ return "paste"; }
	                public void run(){
	                	GrxDebugUtil.println("GrxPluginManager.GrxModelItemClass paste Action");
	                	//paste();
	                }
	            };
	            paste.setEnabled( !isEmptyClipBord() );
	            menu.add( paste );
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return menu;
	}

    /**
     * @brief
     */
	private void dynamicChangeMenu(final Class<? extends GrxBaseItem> cls, Vector<Action> menu)	{
        try {
            Method m = cls.getMethod("paste", String.class);
            Class<?> c = m.getDeclaringClass();
            if( c != GrxBaseItem.class ){
                for(Action action : menu){
                    if( action.getText().equals("paste") ){
                        action.setEnabled(!isEmptyClipBord());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
	}

    /**
     * @brief
     */
	private boolean isEmptyClipBord(){
	    GrxPluginManager.setClipBordVal();
	    return GrxPluginManager.getClipBoardVal().isEmpty();
	}
	/**
	 * @brief shutdown this manager
	 */
	public void shutdown() {
		GrxDebugUtil.println("[PM] shutdown.");

		Iterator<OrderedHashMap> it = pluginMap_.values().iterator();
		for (; it.hasNext();) {
			Iterator it2 = (it.next()).values().iterator();
			for (; it2.hasNext();)
				((GrxBasePlugin) it2.next()).shutdown();
		}

		/* この時点ですでにビューは閉じられている
		 * いまはViewPartのdisposeメソッドからViewのshutdownを呼んでいる
		updateViewList();
		for( GrxBaseView v : selectedViewList_ )
			v.shutdown();
		*/

		try {
			GrxCorbaUtil.getORB().shutdown(false);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * @brief set property associated to key
	 * @param key keyword
	 * @param val property associated to key
	 */
	public void setProjectProperty(String key, String val) {
		currentProject_.setProperty(key, val);
	}

	/**
	 * @brief get property of current project associated to key
	 * @param key
	 * @return property of current project
	 */
	public String getProjectProperty(String key) {
		return currentProject_.getProperty(key);
	}

	/**
	 * @brief get current model name
	 * @return current model name
	 */
	public String getCurrentModeName() {
		if (currentMode_ == null)
			return null;
		return currentMode_.getName();
	}

	/**
	 * @brief get project menu
	 * @return project menu
	 */
	public Vector<Action> getProjectMenu() {
		return currentProject_.getMenu();
	}

	/**
	 * @brief get home path
	 * @return home path
	 */
    public String getHomePath() {
        return homePath_;
    }

    /**
     * @brief restore processes in rc project and current project
     */
	public void restoreProcess() {
		GrxProcessManagerView pmView = (GrxProcessManagerView) getView(GrxProcessManagerView.class);
		if (pmView == null)
			return;

		pmView.loadProcessList(rcProject_.getElement());
		pmView.loadProcessList(currentProject_.getElement());
	}

	/**
	 * @brief get project name
	 * @return project name
	 */
	public String getProjectName(){
		return currentProject_.getName();
	}


	/**
	 * @brief Get selected GrxBaseItem List on tree view
	 * @return List<GrxBaseItem>
	 */
	/* comment out by kanehiro. This class should be independent from any specific view class
	public List<GrxBaseItem> getSelectedGrxBaseItemList(){
		return selectedItemOnTreeViewList_;
	}
	*/

	/**
	 * @brief Paste event 
	 * 
	 */
	/* comment out by kanehiro. This class should be independent from any specific view class
	private void paste(){
		GrxDebugUtil.println("GrxPluginManager.paste.");
		for (Object o : selectedItemOnTreeViewList_.toArray() ){
			if ( GrxModelItem.class.isAssignableFrom( o.getClass() ) ){
				GrxDebugUtil.println("GrxModelItem 存在確認");
			}
		}
	}
	*/
	
    /**
     * @brief Get clip board value
     * @return clip board value
     */
	private static String getClipBoardVal(){
        return GrxPluginManager.clipValue_.get();
     }


    /**
     * @brief Set clip board value GrxPluginManager.clipValue_
     */
    private static void setClipBordVal(){
        Clipboard clp = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable data = clp.getContents(null);
        String ret = "";
        if (data != null && data.isDataFlavorSupported(DataFlavor.stringFlavor) ){
            try {
                ret = (String)data.getTransferData( DataFlavor.stringFlavor );
            } catch(Exception e) {
                GrxDebugUtil.printErr("GrxPluginManager.setClipBordVal: " , e);
            }
        }
        GrxPluginManager.clipValue_.set( ret );
     }

    private static SynchronizedAccessor<String> clipValue_ = new SynchronizedAccessor<String>("");


}
