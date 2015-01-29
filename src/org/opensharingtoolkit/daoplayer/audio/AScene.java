/**
 * 
 */
package org.opensharingtoolkit.daoplayer.audio;

import java.util.Collection;
import java.util.HashMap;

import org.opensharingtoolkit.daoplayer.IAudio;
import org.opensharingtoolkit.daoplayer.IAudio.ITrack;

/**
 * @author pszcmg
 *
 */
public class AScene implements IAudio.IScene {

	private boolean mPartial;
	
	public AScene(boolean mPartial) {
		super();
		this.mPartial = mPartial;
	}

	static class TrackRef {
		private ITrack mTrack;
		private Float mVolume;
		private Integer mPos;
		private Boolean mPrepare;
		public TrackRef(ITrack mTrack, Float mVolume, Integer mPos, Boolean mPrepare) {
			super();
			this.mTrack = mTrack;
			this.mVolume = mVolume;
			this.mPos = mPos;
			this.mPrepare = mPrepare;
		}
		public ITrack getTrack() {
			return mTrack;
		}
		public Float getVolume() {
			return mVolume;
		}
		public Integer getPos() {
			return mPos;
		}	
		public Boolean getPrepare() {
			return mPrepare;			
		}
		
	}
	
	private HashMap<Integer,TrackRef> mTrackRefs= new HashMap<Integer,TrackRef>();
	
	Collection<TrackRef> getTrackRefs() {
		return mTrackRefs.values();
	}
	
	public boolean isPartial() {
		return mPartial;
	}

	@Override
	public void set(ITrack track, Float volume, Integer pos, Boolean prepare) {
		TrackRef tref = new TrackRef(track, volume, pos, prepare);
		mTrackRefs.put(track.getId(), tref);		
	}

	@Override
	public void setVolume(ITrack track, float volume) {
		TrackRef tref = mTrackRefs.get(track.getId());
		if (tref!=null)
			tref.mVolume = volume;
		else 
			set(track, volume, null, null);		
	}

	@Override
	public void setPosition(ITrack track, int pos) {
		TrackRef tref = mTrackRefs.get(track.getId());
		if (tref!=null)
			tref.mPos = pos;
		else 
			set(track, null, pos, null);		
	}
}
