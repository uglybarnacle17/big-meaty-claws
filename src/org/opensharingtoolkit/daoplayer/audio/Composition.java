/**
 * 
 */
package org.opensharingtoolkit.daoplayer.audio;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opensharingtoolkit.daoplayer.IAudio;
import org.opensharingtoolkit.daoplayer.IAudio.IFile;
import org.opensharingtoolkit.daoplayer.IAudio.IScene;
import org.opensharingtoolkit.daoplayer.IAudio.ITrack;
import org.opensharingtoolkit.daoplayer.ILog;
import org.opensharingtoolkit.daoplayer.audio.ATrack.Section;

import android.util.Log;

/** Read a "composition" file.
 * 
 * @author pszcmg
 *
 */
public class Composition {
	private static final String TAG = "daoplayer-compositionreader";

	private static final String CONSTANTS = "constants";
	private static final String CONTEXT = "context";	
	private static final String COST = "cost";	
	private static final String DEFAULT_SCENE = "defaultScene";
	private static final String END_COST = "endCost";	
	private static final String FILES = "files";
	private static final String FILE_POS = "filePos";
	private static final String LENGTH = "length";
	private static final String NAME = "name";
	private static final String NEXT = "next";	
	private static final String ONLOAD = "onload";
	private static final String ONUPDATE = "onupdate";
	private static final String PARTIAL = "partial";
	private static final String PATH = "path";
	private static final String PAUSE_IF_SILENT = "pauseIfSilent";
	private static final String POS = "pos";
	private static final String PREPARE = "prepare";
	private static final String REPEATS = "repeats";
	private static final String SCENES = "scenes";
	private static final String SECTIONS = "sections";
	private static final String START_COST = "startCost";	
	private static final String TRACKS = "tracks";
	private static final String TRACK_POS = "trackPos";
	private static final String UPDATE_PERIOD = "updatePeriod";
	private static final String VOLUME = "volume";
	private static final String WAYPOINTS = "waypoints";

	private AudioEngine mEngine;
	private String mDefaultScene;
	private DynConstants mConstants = new DynConstants();
	private Map<String,ITrack> mTracks = new HashMap<String,ITrack>();
	private Map<String,DynScene> mScenes = new HashMap<String, DynScene>();
	private Vector<String> mScenesInOrder = new Vector<String>();
	private Context mContext;
	private long mFirstSceneLoadTime;
	private long mLastSceneUpdateTime;
	private long mLastSceneLoadTime;
	private UserModel mUserModel;
	
	public Composition(AudioEngine engine, UserModel userModel) {		
		mEngine = engine;
		mUserModel = userModel;
	}
	public static String readFully(File file) throws IOException {
		StringBuilder sb = new StringBuilder();
		Reader br = new InputStreamReader(new BufferedInputStream(new FileInputStream(file)));
		char buf[] = new char[10000];
		while(true) {
			int cnt = br.read(buf);
			if (cnt<0)
				break;
			sb.append(buf, 0, cnt);
		}
		br.close();
		return sb.toString();
	}
	public void read(File file, ILog log) throws IOException, JSONException {
		Log.d(TAG,"read composition from "+file);
		File parent = file.getParentFile();
		String data = readFully(file);
		JSONObject jcomp = new JSONObject(data);
		if (jcomp.has(CONTEXT))
			mContext = Context.parse(jcomp.getJSONObject(CONTEXT));
		else
			// empty context
			mContext = new Context();
		// TODO meta
		mDefaultScene = (jcomp.has(DEFAULT_SCENE) ? jcomp.getString(DEFAULT_SCENE) : null);
		if (jcomp.has(CONSTANTS))
			mConstants.parse(jcomp.getJSONObject(CONSTANTS));
		mTracks = new HashMap<String,ITrack>();
		if (jcomp.has(TRACKS)) {
			JSONArray jtracks = jcomp.getJSONArray(TRACKS);
			for (int ti=0; ti<jtracks.length(); ti++) {
				JSONObject jtrack = jtracks.getJSONObject(ti);
				String name = jtrack.has(NAME) ? jtrack.getString(NAME) : null;
				boolean pauseIfSilent = jtrack.has(PAUSE_IF_SILENT) && jtrack.getBoolean(PAUSE_IF_SILENT);
				ATrack atrack = (ATrack)mEngine.addTrack(pauseIfSilent);
				if (name!=null)
					mTracks.put(name, atrack);
				else
					Log.w(TAG,"Unnamed track "+ti);
				if (jtrack.has(FILES)) {
					JSONArray jfiles = jtrack.getJSONArray(FILES);
					for (int fi=0; fi<jfiles.length(); fi++) {
						JSONObject jfile = jfiles.getJSONObject(fi);
						if (!jfile.has(PATH)) {
							log.logError("track "+ti+" references unspecified file "+fi);
							continue;
						}
						String fpath = jfile.getString(PATH);
						IFile afile = mEngine.addFile(new File(parent, fpath).getCanonicalPath());
						int trackPos = jfile.has(TRACK_POS) ? mEngine.secondsToSamples(jfile.getDouble(TRACK_POS)) : 0;
						int filePos = jfile.has(FILE_POS) ? mEngine.secondsToSamples(jfile.getDouble(FILE_POS)) : 0;
						int length = jfile.has(LENGTH) ? (jfile.getInt(LENGTH)<0 ? jfile.getInt(LENGTH) : mEngine.secondsToSamples(jfile.getDouble(LENGTH))) : IAudio.ITrack.LENGTH_ALL;
						int repeats = jfile.has(REPEATS) ? jfile.getInt(REPEATS) : 1;
						atrack.addFileRef(trackPos, afile, filePos, length, repeats);
					}
				}
				if (jtrack.has(SECTIONS)) {
					JSONArray jsections = jtrack.getJSONArray(SECTIONS);
					Section lastSection = null;
					for (int fi=0; fi<jsections.length(); fi++) {
						JSONObject jsection = jsections.getJSONObject(fi);
						if (!jsection.has(NAME)) {
							log.logError("track "+ti+" references unnamed section "+fi);
							continue;
						}
						String sname = jsection.getString(NAME);
						int trackPos = jsection.has(TRACK_POS) ? mEngine.secondsToSamples(jsection.getDouble(TRACK_POS)) : 0;
						if (lastSection!=null && lastSection.mLength == IAudio.ITrack.LENGTH_ALL)
							lastSection.mLength = (trackPos > lastSection.mTrackPos) ? trackPos-lastSection.mTrackPos : 0;
						int length = jsection.has(LENGTH) ? (jsection.getInt(LENGTH)<0 ? jsection.getInt(LENGTH) : mEngine.secondsToSamples(jsection.getDouble(LENGTH))) : IAudio.ITrack.LENGTH_ALL;
						double startCost = jsection.has(START_COST) ? jsection.getDouble(START_COST) : Double.MAX_VALUE;
						double endCost = jsection.has(END_COST) ? jsection.getDouble(END_COST) : Double.MAX_VALUE;
						Section section = new Section(sname, trackPos, length, startCost, endCost);
						if (jsection.has(NEXT)) {
							JSONArray jnext = jsection.getJSONArray(NEXT);
							for (int ni=0; ni<jnext.length(); ni++) {
								JSONObject jnextSection = jnext.getJSONObject(ni);
								if (!jnextSection.has(NAME)) {
									log.logError("track "+ti+" section "+sname+" has unnamed next section "+ni);
									continue;
								}
								String nextName = jnextSection.getString(NAME);
								double cost = jsection.has(COST) ? jsection.getDouble(COST) : 0;
								section.addNext(nextName, cost);
							}
						}
						atrack.addSection(section);
						lastSection = section;
					}
				}
			}
		}
		mScenes = new HashMap<String, DynScene>();
		mScenesInOrder = new Vector<String>();
		if (jcomp.has(SCENES)) {
			JSONArray jscenes = jcomp.getJSONArray(SCENES);
			for (int si=0; si<jscenes.length(); si++) {
				JSONObject jscene = jscenes.getJSONObject(si);
				String name = jscene.has(NAME) ? jscene.getString(NAME) : null;
				mScenesInOrder.add(name);
				boolean partial = jscene.has(PARTIAL) && jscene.getBoolean(PARTIAL);
				DynScene ascene = new DynScene(partial);
				if (name!=null)
					mScenes.put(name, ascene);
				else 
					log.logError("Unnamed scene "+si);
				if (jscene.has(CONSTANTS)) {
					DynConstants cons = new DynConstants();
					cons.parse(jscene.getJSONObject(CONSTANTS));
					ascene.setConstants(cons);
				}
				if (jscene.has(UPDATE_PERIOD))
					ascene.setUpdatePeriod(jscene.getDouble(UPDATE_PERIOD));
				if (jscene.has(ONLOAD))
					ascene.setOnload(jscene.getString(ONLOAD));
				if (jscene.has(ONUPDATE))
					ascene.setOnupdate(jscene.getString(ONUPDATE));
				Map<String,String> waypoints = new HashMap<String,String>();
				if (jscene.has(WAYPOINTS)) {
					JSONObject jwaypoints = jscene.getJSONObject(WAYPOINTS);
					Iterator<String> keys = jwaypoints.keys();
					while (keys.hasNext()) {
						String key = keys.next();
						String value = jwaypoints.getString(key);
						waypoints.put(key, value);
					}
				}
				ascene.setWaypoints(waypoints);
				if (jscene.has(TRACKS)) {
					JSONArray jtracks = jscene.getJSONArray(TRACKS);
					for (int ti=0; ti<jtracks.length(); ti++) {
						JSONObject jtrack = jtracks.getJSONObject(ti);
						String trackName = jtrack.getString(NAME);
						ITrack atrack = mTracks.get(trackName);
						if (atrack==null) {
							log.logError("Scene "+name+" refers to unknown track "+trackName);
						} else {
							Log.d(TAG,"Scene "+name+" uses track "+atrack.getId()+" as "+trackName);
							Integer pos = jtrack.has(POS) && jtrack.get(POS) instanceof Number ? mEngine.secondsToSamples(jtrack.getDouble(POS)) : null;
							String dynPos = jtrack.has(POS) && jtrack.get(POS) instanceof String ? jtrack.getString(POS) : null;
							Float volume = jtrack.has(VOLUME) && jtrack.get(VOLUME) instanceof Number ? (float)jtrack.getDouble(VOLUME) : null;
							String dynVolume = jtrack.has(VOLUME) && jtrack.get(VOLUME) instanceof String ? jtrack.getString(VOLUME) : null;
							Boolean prepare = jtrack.has(PREPARE) && jtrack.get(PREPARE) instanceof Boolean ? jtrack.getBoolean(PREPARE) : null;
							ascene.set(atrack, volume, dynVolume, pos, dynPos, prepare);
						}
					}
				}
			}
		}
		log.log("Read composition "+file);
	}
	
	/**
	 * @return the context
	 */
	public Context getContext() {
		return mContext;
	}
	/**
	 * @return the mDefaultScene
	 */
	public String getDefaultScene() {
		return mDefaultScene;
	}
	private static float MIN_VOLUME = 0.0f;
	private static float MAX_VOLUME = 16.0f;
	static class DynInfo {
		Float volume;
		float [] pwlVolume;
		int [] align;
	}
	/** value in map is either null, Float (single volume) or float[] (array of args for pwl) */
	private Map<Integer,DynInfo> getDynInfo(IScriptEngine scriptEngine, DynScene scene, boolean loadFlag, String position, long time) {
		AudioEngine.StateRec srec = mEngine.getNextState();
		AState astate = (srec!=null ? srec.getState() : null);
		StringBuilder sb = new StringBuilder();
		// "built-in"
		sb.append("var pwl=window.pwl;\n");
		sb.append("var position=");
		sb.append(position);
		sb.append(";\n");
		sb.append("var distance=function(coord1,coord2){return window.distance(coord1,coord2 ? coord2 : position);};\n");
		sb.append("var sceneTime=");
		double sceneTime = (srec==null) ? 0 : srec.mSceneTime+mEngine.samplesToSeconds(mEngine.getFutureOffset());
		sb.append(sceneTime);
		sb.append(";\n");
		sb.append("var totalTime=");
		if (srec!=null)
			sb.append(srec.mTotalTime+mEngine.samplesToSeconds(mEngine.getFutureOffset()));
		else
			sb.append("0");
		sb.append(";\n");
		mUserModel.toJavascript(sb, scene.getWaypoints());
		// constants: composition, scene
		mConstants.toJavascript(sb);
		if (scene.getConstants()!=null)
			scene.getConstants().toJavascript(sb);
		if (loadFlag && scene.getOnload()!=null) {
			sb.append(scene.getOnload());
			sb.append(";\n");
		} else if (!loadFlag && scene.getOnupdate()!=null) {
			sb.append(scene.getOnupdate());
			sb.append(";\n");			
		}
		sb.append("var vs={};\n");
		sb.append("var ps={};\n");
		for (DynScene.TrackRef tr : scene.getTrackRefs()) {
			String dynVolume = tr.getDynVolume();
			String dynPos = tr.getDynPos();
			if (dynVolume!=null || dynPos!=null) {
				int trackPos = 0;
				if (loadFlag && tr.getPos()!=null)
					trackPos = tr.getPos();
				else if (loadFlag && !scene.isPartial())
					trackPos = 0;
				else if (astate!=null) {
					AState.TrackRef atr = astate.get(tr.getTrack());
					if (atr!=null) {
						trackPos = atr.getPos();
						if (!atr.isPaused())
							trackPos += mEngine.getFutureOffset();
					}
				}
				if (dynVolume!=null) {
					sb.append("vs['");
					sb.append(tr.getTrack().getId());
					sb.append("']=(function(trackTime){return(");
					sb.append(dynVolume);
					sb.append(");})(");
					sb.append(mEngine.samplesToSeconds(trackPos));
					sb.append(");\n");
				}
				if (dynPos!=null) {
					// current section just based on trackTime
					Section section = null;
					ATrack atrack = (ATrack)tr.getTrack();
					for (Section s : atrack.getSections().values()) {
						if (trackPos>=s.mTrackPos && (s.mLength==IAudio.ITrack.LENGTH_ALL || trackPos-s.mTrackPos<s.mLength)) {
							section = s;
							Log.d(TAG,"Pos "+trackPos+" in section "+s.mName+" ("+s.mTrackPos+" + "+s.mLength+")");
							break;
						}
						else
							Log.d(TAG,"Pos "+trackPos+" not in section "+s.mName+" ("+s.mTrackPos+" + "+s.mLength+")");
					}
					double trackTime = mEngine.samplesToSeconds(trackPos);
					sb.append("ps['");
					sb.append(tr.getTrack().getId());
					sb.append("']=(function(trackTime,currentSection){return(");
					sb.append(dynPos);
					sb.append(");})(");
					sb.append(trackTime);
					sb.append(",");
					if (section==null)
						sb.append("null");
					else {
						sb.append("{name:");
						sb.append(escapeJavascriptString(section.mName));
						sb.append(",startTime:");
						sb.append(sceneTime+mEngine.samplesToSeconds(section.mTrackPos)-trackTime);
						sb.append(",endTime:");
						sb.append(sceneTime+mEngine.samplesToSeconds(section.mTrackPos+section.mLength)-trackTime);
						sb.append("}");
					}
					sb.append(");\n");
				}
			}			
		}
		sb.append("return JSON.stringify({vs:vs,ps:ps});");
		String res = scriptEngine.runScript(sb.toString());
		Log.d(TAG,"run script: "+res+"="+sb.toString());
		Map<Integer,DynInfo> dynInfos = new HashMap<Integer,DynInfo>();
		try {
			JSONObject jres = new JSONObject(res);
			JSONObject vs = jres.getJSONObject("vs");
			Iterator<String> keys = vs.keys();
			while(keys.hasNext()) {
				String key = keys.next();
				int id = Integer.valueOf(key);
				DynInfo di = new DynInfo();
				dynInfos.put(id,  di);
				Object val = vs.get(key);
				if (val instanceof JSONArray) {
					JSONArray aval = (JSONArray)val;
					float fvals[] = new float[aval.length()];
					for (int i=0; i<aval.length(); i+=2) {
						fvals[i] = extractFloat(aval.get(i));
						if (i+1<aval.length())
							fvals[i+1] = clipVolume(extractFloat(aval.get(i+1)));
					}
					di.pwlVolume = fvals;
				} else {
					float fval = clipVolume(extractFloat(val));
					di.volume = fval;
				}
			}
			JSONObject ps = jres.getJSONObject("ps");
			keys = ps.keys();
			while(keys.hasNext()) {
				String key = keys.next();
				int id = Integer.valueOf(key);
				DynInfo di = dynInfos.get(id);
				if (di==null) {
					di = new DynInfo();
					dynInfos.put(id,  di);
				}
				Object val = ps.get(key);
				if (val instanceof JSONArray) {
					JSONArray aval = (JSONArray)val;
					int ivals[] = new int[aval.length()];
					for (int i=0; i<aval.length(); i++) {
						ivals[i] = (int)mEngine.secondsToSamples((double)extractFloat(aval.get(i)));
					}
					di.align = ivals;
				} else if (val!=null) {
					float fval = extractFloat(val);
					//Log.d(TAG,"dynPos single value "+fval+"-> array");
					di.align = new int[2];
					di.align[0] = (int)mEngine.secondsToSamples(srec!=null ? srec.mSceneTime : 0);
					di.align[1] = (int)mEngine.secondsToSamples((double)fval);
				} else {
					//Log.d(TAG,"dynPos null");
				}
			}
		}
		catch (Exception e) {
			Log.w(TAG,"error parsing load script result "+res+": "+e);
			mEngine.getLog().logError("Script returned error: "+res+", from "+sb.toString());
		}
		return dynInfos;
	}
	private String escapeJavascriptString(String mName) {
		StringBuilder sb = new StringBuilder();
		sb.append("'");
		sb.append(mName.replace("\\", "\\\\").replace("'", "\\'"));
		sb.append("'");
		return sb.toString();
	}
	private float extractFloat(Object val) {
		if (val instanceof Number) {
			return ((Number)val).floatValue();
		}
		else if (val instanceof String) {
			try {
				float fval = Float.valueOf((String)val);
				return fval;
			}
			catch (NumberFormatException nfe) {
				mEngine.getLog().logError("Script returned non-number "+val);
				return 0;
			}				
		}
		else {
			mEngine.getLog().logError("Script returned non-number/string "+val);
			return 0;			
		}
	}
	private float clipVolume(float fval) {
		if (fval<MIN_VOLUME)
			fval = MIN_VOLUME;
		if (fval>MAX_VOLUME)
			fval = MAX_VOLUME;
		return fval;
	}
	public boolean setScene(String name, String position, IScriptEngine scriptEngine) {
		DynScene scene = mScenes.get(name);
		if (scene==null) {
			Log.w(TAG, "setScene unknown "+name);
			return false;			
		}
		AScene ascene = mEngine.newAScene(scene.isPartial());
		mLastSceneLoadTime = System.currentTimeMillis();
		if (mFirstSceneLoadTime==0)
			mFirstSceneLoadTime = mLastSceneLoadTime;
		mLastSceneUpdateTime = mLastSceneLoadTime;
		Map<Integer,DynInfo> dynInfos = getDynInfo(scriptEngine, scene, true, position, mLastSceneLoadTime);
		for (DynScene.TrackRef tr : scene.getTrackRefs()) {
			DynInfo di = dynInfos.get(tr.getTrack().getId());
			if (di!=null && di.volume!=null) {
				if (di.align!=null)
					ascene.set(tr.getTrack(), di.volume, di.align, tr.getPrepare());
				else
					ascene.set(tr.getTrack(), di.volume, tr.getPos(), tr.getPrepare());
			}
			else if (di!=null && di.pwlVolume!=null) {
				if (di.align!=null)
					ascene.set(tr.getTrack(), di.pwlVolume, di.align, tr.getPrepare());
				else
					ascene.set(tr.getTrack(), di.pwlVolume, tr.getPos(), tr.getPrepare());
			}
			else if (di!=null && di.align!=null)
				ascene.set(tr.getTrack(), tr.getVolume(), di.align, tr.getPrepare());
			else
				ascene.set(tr.getTrack(), tr.getVolume(), tr.getPos(), tr.getPrepare());
		}
		mEngine.setScene(ascene, true);
		return true;
	}
	public boolean updateScene(String name, String position, IScriptEngine scriptEngine) {
		DynScene scene = mScenes.get(name);
		if (scene==null) {
			Log.w(TAG, "updateScene unknown "+name);
			return false;			
		}
		// partial
		AScene ascene = mEngine.newAScene(true);
		mLastSceneUpdateTime = System.currentTimeMillis();
		Map<Integer,DynInfo> dynInfos = getDynInfo(scriptEngine, scene, false, position, mLastSceneUpdateTime);
		for (DynScene.TrackRef tr : scene.getTrackRefs()) {
			DynInfo di = dynInfos.get(tr.getTrack().getId());
			if (di!=null && di.volume!=null) {
				if (di.align!=null)
					ascene.set(tr.getTrack(), di.volume, di.align, (Boolean)null);
				else
					ascene.set(tr.getTrack(), di.volume, (Integer)null, null);
			}
			else if (di!=null && di.pwlVolume!=null) {
				if (di.align!=null)
					ascene.set(tr.getTrack(), di.pwlVolume, di.align, null);
				else
					ascene.set(tr.getTrack(), di.pwlVolume, (Integer)null, null);
			}
			else if (di!=null && di.align!=null)
				ascene.set(tr.getTrack(), (Float)null, di.align, null);
		}
		mEngine.setScene(ascene, false);
		return true;
	}
	public Long getSceneUpdateDelay(String name) {
		DynScene scene = mScenes.get(name);
		if (scene==null) {
			Log.w(TAG, "getSceneUpdateDelay unknown "+name);
			return null;			
		}
		Double period = scene.getUpdatePeriod();
		if (period==null || period<=0)
			return null;
		long now = System.currentTimeMillis();
		long elapsed = now-mLastSceneUpdateTime;
		long delay = ((long)(period*1000))-elapsed;
		if (delay<0)
			return 0L;
		return delay;
	}
	public String getNextScene(String name) {
		int ix = mScenesInOrder.indexOf(name);
		if (ix<0) {
			Log.w(TAG,"Could not find scene "+name);
			if (mScenesInOrder.size()>0)
				return mScenesInOrder.get(0);
			return null;
		}
		else {
			ix = ix + 1;
			if (ix >= mScenesInOrder.size())
				ix = 0;
			return mScenesInOrder.get(ix);
		}			
	}
	public String getPrevScene(String name) {
		int ix = mScenesInOrder.indexOf(name);
		if (ix<0) {
			Log.w(TAG,"Could not find scene "+name);
			if (mScenesInOrder.size()>0)
				return mScenesInOrder.get(0);
			return null;
		}
		else {
			ix = ix - 1;
			if (ix <0)
				ix = mScenesInOrder.size() - 1;
			return mScenesInOrder.get(ix);
		}			
	}
	public String getTestTrack(String name) {
		DynScene scene = mScenes.get(name);
		if (scene==null) {
			Log.w(TAG, "getTestTrack: scene unknown "+name);
			return null;			
		}
		for (DynScene.TrackRef tr : scene.getTrackRefs()) {
			if (tr.getVolume()!=null && tr.getVolume()<=0)
				continue;
			ATrack track = (ATrack)tr.getTrack();
			ATrack.FileRef fr = track.mFileRefs.first();
			if (fr!=null)
				return fr.mFile.getPath();
		}
		return null;
	}
}
