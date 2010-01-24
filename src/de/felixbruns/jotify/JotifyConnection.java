package de.felixbruns.jotify;

import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;

import de.felixbruns.jotify.cache.*;
import de.felixbruns.jotify.crypto.*;
import de.felixbruns.jotify.exceptions.*;
import de.felixbruns.jotify.media.*;
import de.felixbruns.jotify.media.parser.XMLMediaParser;
import de.felixbruns.jotify.media.parser.XMLPlaylistParser;
import de.felixbruns.jotify.media.parser.XMLUserParser;
import de.felixbruns.jotify.player.*;
import de.felixbruns.jotify.player.http.HTTPStreamPlayer;
import de.felixbruns.jotify.protocol.*;
import de.felixbruns.jotify.protocol.channel.*;
import de.felixbruns.jotify.util.*;

public class JotifyConnection implements Jotify, CommandListener {
	private Session   session;
	private Protocol  protocol;
	private User      user;
	private Semaphore userSemaphore;
	private Player    player;
	private Cache     cache;
	private float     volume;
	private long      timeout;
	private TimeUnit  unit;
	
	/**
	 * Enum for browsing media.
	 */
	private enum BrowseType {
		ARTIST(1), ALBUM(2), TRACK(3);
		
		private int value;
		
		private BrowseType(int value){
			this.value = value;
		}
		
		public int getValue(){
			return this.value;
		}
	}
	
	/**
	 * Create a new Jotify instance using the default {@link Cache}
	 * implementation and timeout value (10 seconds).
	 */
	public JotifyConnection(){
		this(new FileCache(), 10, TimeUnit.SECONDS);
	}
	
	/**
	 * Create a new Jotify instance using a specified {@link Cache}
	 * implementation and timeout. Note: A {@link TimeoutException}
	 * may also be caused by geographical restrictions.
	 * 
	 * @param cache   Cache implementation to use.
	 * @param timeout Timeout value to use.
	 * @param unit    TimeUnit to use for timeout.
	 * 
	 * @see MemoryCache
	 * @see FileCache
	 */
	public JotifyConnection(Cache cache, long timeout, TimeUnit unit){
		this.session       = new Session();
		this.protocol      = null;
		this.user          = null;
		this.userSemaphore = new Semaphore(2);
		this.player        = null;
		this.cache         = cache;
		this.volume        = 1.0f;
		this.timeout       = timeout;
		this.unit          = unit;
		
		/* Acquire permits (country, prodinfo). */
		this.userSemaphore.acquireUninterruptibly(2);
	}
	
	/**
	 * Set timeout for requests.
	 * 
	 * @param timeout Timeout value to use.
	 * @param unit    TimeUnit to use for timeout.
	 */
	public void setTimeout(long timeout, TimeUnit unit){
		this.timeout = timeout;
		this.unit    = unit;
	}
	
	/**
	 * Login to Spotify using the specified username and password.
	 * 
	 * @param username Username to use.
	 * @param password Corresponding password.
	 * 
	 * @throws ConnectionException
	 * @throws AuthenticationException
	 */
	public void login(String username, String password) throws ConnectionException, AuthenticationException {
		/* Check if we're already logged in. */
		if(this.protocol != null){
			throw new IllegalStateException("Already logged in!");
		}
		
		/* Authenticate session and get protocol. */
		this.protocol = this.session.authenticate(username, password);
		
		/* Create user object. */
		this.user = new User(username);
		
		/* Add command handler. */
		this.protocol.addListener(this);
	}
	
	/**
	 * Closes the connection to a Spotify server.
	 * 
	 * @throws ConnectionException
	 */
	public void close() throws ConnectionException {
		/* This will make receivePacket return immediately. */
		if(this.protocol != null){
			this.protocol.disconnect();
		}
		
		/* Reset protocol to 'null'. */
		this.protocol = null;
	}
	
	/**
	 * Continuously receives packets in order to handle them.
	 * Use a {@link Thread} to run this.
	 */
	public void run(){
		/* Check if we're logged in. */
		if(this.protocol == null){
			throw new IllegalStateException("You need to login first!");
		}
		
		/* Continuously receive packets until connection is closed. */
		try{
			while(true){
				if(this.protocol == null){
					break;
				}
				
				this.protocol.receivePacket();
			}
		}
		catch(ProtocolException e){
			/* Connection was closed. */
		}
	}
	
	/**
	 * Get user info.
	 * 
	 * @return A {@link User} object.
	 * 
	 * @see User
	 */
	public User user(){
		/* Wait for data to become available (country, prodinfo). */
		try{
			if(!this.userSemaphore.tryAcquire(2, this.timeout, this.unit)){
				throw new TimeoutException("Timeout while waiting for user data.");
			}
		}
		catch(InterruptedException e){
			throw new RuntimeException(e);
		}
		catch(TimeoutException e){
			throw new RuntimeException(e);
		}
		
		/* Release so this can be called again. */
		this.userSemaphore.release(2);
		
		return this.user;
	}
	
	/**
	 * Fetch a toplist.
	 * 
	 * @param type     A toplist type. e.g. "artist", "album" or "track".
	 * @param region   A region code or null. e.g. "SE" or "DE".
	 * @param username A username or null.
	 * 
	 * @return A {@link Result} object.
	 * 
	 * @see Result
	 */
	public Result toplist(String type, String region, String username){
		/* Create channel callback and parameter map. */
		ChannelCallback callback   = new ChannelCallback();
		Map<String, String> params = new HashMap<String, String>();
		
		/* Add parameters. */
		params.put("type", type);
		params.put("region", region);
		params.put("username", username);
		
		/* Send toplist request. */
		try{
			this.protocol.sendToplistRequest(callback, params);
		}
		catch(ProtocolException e){
			return null;
		}
		
		/* Get data. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Create result from XML. */
		return XMLMediaParser.parseResult(data, "UTF-8");
	}
	
	/**
	 * Search for an artist, album or track.
	 * 
	 * @param query Your search query.
	 * 
	 * @return A {@link Result} object.
	 * 
	 * @see Result
	 */
	public Result search(String query){
		/* Create channel callback. */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send search query. */
		try{
			this.protocol.sendSearchQuery(callback, query);
		}
		catch(ProtocolException e){
			return null;
		}
		
		/* Get data. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Create result from XML. */
		Result result = XMLMediaParser.parseResult(data, "UTF-8");
		
		result.setQuery(query);
		
		return result;
	}
	
	/**
	 * Get an image (e.g. artist portrait or cover) by requesting
	 * it from the server or loading it from the local cache, if
	 * available.
	 * 
	 * @param id Id of the image to get.
	 * 
	 * @return An {@link Image} or null if the request failed.
	 * 
	 * @see Image
	 */
	public Image image(String id){
		/* Data buffer. */
		byte[] data;
		
		/* Check cache. */
		if(this.cache != null && this.cache.contains("image", id)){
			data = this.cache.load("image", id);
		}
		else{
			/* Create channel callback. */
			ChannelCallback callback = new ChannelCallback();
			
			/* Send image request. */
			try{
				this.protocol.sendImageRequest(callback, id);
			}
			catch(ProtocolException e){
				return null;
			}
			
			/* Get data. */
			data = callback.get(this.timeout, this.unit);
			
			/* Save to cache. */
			if(this.cache != null){
				this.cache.store("image", id, data);
			}
		}
		
		/* Create Image. */
		try{
			return ImageIO.read(new ByteArrayInputStream(data));
		}
		catch(IOException e){
			return null;
		}
	}
	
	/**
	 * Browse artist, album or track info.
	 * 
	 * @param type Type of media to browse for.
	 * @param id   Id of media to browse.
	 * 
	 * @return An {@link XMLElement} object holding the data or null
	 *         on failure.
	 * 
	 * @see BrowseType
	 */
	private Object browse(BrowseType type, String id){
		/* Create channel callback. */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send browse request. */
		try{
			this.protocol.sendBrowseRequest(callback, type.getValue(), id);
		}
		catch(ProtocolException e){
			return null;
		}
		
		/* Create object from XML. */
		return XMLMediaParser.parse(
			callback.get(this.timeout, this.unit), "UTF-8"
		);
	}
	
	/**
	 * Browse artist info by id.
	 * 
	 * @param id An id identifying the artist to browse.
	 * 
	 * @retrun An {@link Artist} object holding more information about
	 *         the artist or null on failure.
	 * 
	 * @see Artist
	 */
	public Artist browseArtist(String id){
		/* Browse. */
		Object artist = this.browse(BrowseType.ARTIST, id);
		
		if(artist instanceof Artist){
			return (Artist)artist;
		}
		
		return null;
	}
	
	/**
	 * Browse artist info.
	 * 
	 * @param artist An {@link Artist} object identifying the artist to browse.
	 * 
	 * @retrun A new {@link Artist} object holding more information about
	 *         the artist or null on failure.
	 * 
	 * @see Artist
	 */
	public Artist browse(Artist artist){
		return this.browseArtist(artist.getId());
	}
	
	/**
	 * Browse album info by id.
	 * 
	 * @param id An id identifying the album to browse.
	 * 
	 * @retrun An {@link Album} object holding more information about
	 *         the album or null on failure.
	 * 
	 * @see Album
	 */
	public Album browseAlbum(String id){
		/* Browse. */
		Object album = this.browse(BrowseType.ALBUM, id);
		
		if(album instanceof Album){
			return (Album)album;
		}
		
		return null;
	}
	
	/**
	 * Browse album info.
	 * 
	 * @param album An {@link Album} object identifying the album to browse.
	 * 
	 * @retrun A new {@link Album} object holding more information about
	 *         the album or null on failure.
	 * 
	 * @see Album
	 */
	public Album browse(Album album){
		return this.browseAlbum(album.getId());
	}
	
	/**
	 * Browse track info by id.
	 * 
	 * @param id An id identifying the track to browse.
	 * 
	 * @return A {@link Track} object or null on failure.
	 * 
	 * @see Track
	 */
	public Track browseTrack(String id){
		/* Browse. */
		Object object = this.browse(BrowseType.TRACK, id);
		
		if(object instanceof Result){
			Result result = (Result)object;
			
			if(result.getTracks().isEmpty()){
				return null;
			}
			
			return result.getTracks().get(0);
		}
		
		return null;
	}
	
	/**
	 * Browse track info.
	 * 
	 * @param album A {@link Track} object identifying the track to browse.
	 * 
	 * @return A {@link Track} object or null on failure.
	 * 
	 * @see Track
	 */
	public Track browse(Track track){
		return this.browseTrack(track.getId());
	}
	
	/**
	 * Browse information for multiple tracks by id.
	 * 
	 * @param ids A {@link List} of ids identifying the tracks to browse.
	 * 
	 * @return A list of {@link Track} objects or null on failure.
	 * 
	 * @see Track
	 */
	public List<Track> browseTracks(List<String> ids){
		/* Data buffer. */
		byte[] data;
		
		/* Create cache hash. */
		String hash = "";
		
		for(String id : ids){
			hash += id;
		}
		
		hash = Hex.toHex(Hash.sha1(Hex.toBytes(hash)));
		
		/* Check cache. */
		if(this.cache != null && this.cache.contains("browse", hash)){
			data = this.cache.load("browse", hash);
		}
		else{
			/* Create channel callback */
			ChannelCallback callback = new ChannelCallback();
			
			/* Send browse request. */
			try{
				this.protocol.sendBrowseRequest(callback, BrowseType.TRACK.getValue(), ids);
			}
			catch(ProtocolException e){
				return null;
			}
			
			/* Get data. */
			data = callback.get(this.timeout, this.unit);
			
			/* Save to cache. */
			if(this.cache != null){
				this.cache.store("browse", hash, data);
			}
		}
		
		/* Create result from XML. */
		return XMLMediaParser.parseResult(data, "UTF-8").getTracks();
	}
	
	/**
	 * Browse information for multiple tracks.
	 * 
	 * @param tracks A {@link List} of {@link Track} objects identifying
	 *               the tracks to browse.
	 * 
	 * @retrun A {@link Result} object holding more information about
	 *         the tracks or null on failure.
	 * 
	 * @see Track
	 */
	public List<Track> browse(List<Track> tracks){
		/* Create id list. */
		List<String> ids = new ArrayList<String>();
		
		for(Track track : tracks){
			ids.add(track.getId());
		}
		
		return this.browseTracks(ids);
	}
	
	/**
	 * Get stored user playlists.
	 * 
	 * @return A {@link PlaylistContainer} holding {@link Playlist} objects
	 *         or an empty {@link PlaylistContainer} on failure.
	 *         Note: {@link Playlist} objects only hold id and author and need
	 *         to be loaded using {@link #playlist(String)}.
	 * 
	 * @throws TimeoutException  
	 * 
	 * @see PlaylistContainer
	 */
	public PlaylistContainer playlistContainer(){
		/* Create channel callback. */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send request and parse response. */
		try{
			this.protocol.sendPlaylistRequest(callback, null);
			
			/* Create and return playlist. */
			return XMLPlaylistParser.parsePlaylistContainer(
				callback.get(this.timeout, this.unit), "UTF-8"
			);
		}
		catch(ProtocolException e){
			return PlaylistContainer.EMPTY;
		}
	}
	
	/**
	 * Add a playlist to a playlist container.
	 * 
	 * @param playlistContainer A {@link PlaylistContainer} to add the playlist to.
	 * @param playlist          The {@link Playlist} to be added.
	 * 
	 * @return true on success and false on failure.
	 * 
	 * @see PlaylistContainer
	 */
	public boolean playlistContainerAddPlaylist(PlaylistContainer playlistContainer, Playlist playlist){
		return this.playlistContainerAddPlaylist(playlistContainer, playlist, playlistContainer.getPlaylists().size());
	}
	
	/**
	 * Add a playlist to a playlist container.
	 * 
	 * @param playlistContainer The playlist container.
	 * @param playlist          The playlist to be added.
	 * @param position          The target position of the added playlist.
	 * 
	 * @return true on success and false on failure.
	 */
	public boolean playlistContainerAddPlaylist(PlaylistContainer playlistContainer, Playlist playlist, int position){
		List<Playlist> playlists = new ArrayList<Playlist>();
		
		playlists.add(playlist);
		
		return this.playlistContainerAddPlaylists(playlistContainer, playlists, position);
	}
	
	/**
	 * Add multiple playlists to a playlist container.
	 * 
	 * @param playlistContainer The playlist container.
	 * @param playlists         A {@link List} of playlists to be added.
	 * @param position          The target position of the added playlists.
	 * 
	 * @return true on success and false on failure.
	 */
	public boolean playlistContainerAddPlaylists(PlaylistContainer playlistContainer, List<Playlist> playlists, int position){
		String  user      = this.session.getUsername();
		long    timestamp = new Date().getTime() / 1000;
		
		/* Add the playlists for new checksum calculation. */
		playlistContainer.getPlaylists().addAll(position, playlists);
		
		/* Build a comma separated list of tracks and append '01' to every id!. */
		String playlistList = "";
		
		for(int i = 0; i < playlists.size(); i++){
			playlistList += ((i > 0)?",":"") + playlists.get(i).getId() + "02";
		}
		
		/* Create XML builder. */
		XMLBuilder xml = XMLBuilder.create()
			.element("change")
				.element("ops")
					.element("add")
						.element("i", "%d", position).up()
						.element("items", playlistList).up()
					.up()
				.up()
				.element("time", "%d", timestamp).up()
				.element("user", user).up()
			.up()
			.element(
				"version", "%010d,%010d,%010d,0",
				playlistContainer.getRevision() + 1,
				playlistContainer.getPlaylists().size(),
				playlistContainer.getChecksum()
			).up();
		
		/* Remove the tracks again. */
		for(int i = 0; i < playlists.size(); i++){
			playlistContainer.getPlaylists().remove(position);
		}
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send change playlist request. */
		try{
			this.protocol.sendChangePlaylistContainer(callback, playlistContainer, xml.toString());
		}
		catch(ProtocolException e){
			return false;
		}
		
		/* Get response. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Check confirmation. */
		PlaylistConfirmation confirmation = XMLPlaylistParser.parseConfirmation(data, "UTF-8");
		
		if(confirmation == null){
			return false;
		}
		
		/* Add the tracks, since operation was successful. */
		playlistContainer.getPlaylists().addAll(position, playlists);
		
		/* Set new revision. */
		playlistContainer.setRevision(confirmation.getRevision());
		
		return true;
	}
	
	// TODO: playlistsMovePlaylist(s)
	
	/**
	 * Remove a playlist from a playlist container.
	 * 
	 * @param playlistContainer The playlist container.
	 * @param position          The position of the playlist to remove.
	 * 
	 * @return true on success and false on failure.
	 */
	public boolean playlistContainerRemovePlaylist(PlaylistContainer playlistContainer, int position){
		return this.playlistContainerRemovePlaylists(playlistContainer, position, 1);
	}
	
	/**
	 * Remove multiple playlists from a playlist container.
	 * 
	 * @param playlistContainer The playlist container.
	 * @param position          The position of the tracks to remove.
	 * @param count             The number of track to remove.
	 * 
	 * @return true on success and false on failure.
	 */
	public boolean playlistContainerRemovePlaylists(PlaylistContainer playlistContainer, int position, int count){
		String user      = this.session.getUsername();
		long   timestamp = new Date().getTime() / 1000;
		
		/* Create a sublist view (important!) and clone it by constructing a new ArrayList. */
		List<Playlist> playlists = new ArrayList<Playlist>(
			playlistContainer.getPlaylists().subList(position, position + count)
		);
		
		/* First remove the playlist(s) to calculate the new checksum. */
		for(int i = 0; i < playlists.size(); i++){
			playlistContainer.getPlaylists().remove(position);
		}
		
		/* Create XML builder. */
		XMLBuilder xml = XMLBuilder.create()
			.element("change")
				.element("ops")
					.element("del")
						.element("i", "%d", position).up()
						.element("k", "%d", count).up()
					.up()
				.up()
				.element("time", "%d", timestamp).up()
				.element("user", user).up()
			.up()
			.element(
				"version", "%010d,%010d,%010d,0",
				playlistContainer.getRevision() + 1,
				playlistContainer.getPlaylists().size(),
				playlistContainer.getChecksum()
			).up();
		
		/* Add the playlist(s) again, because we need the old checksum for sending. */
		playlistContainer.getPlaylists().addAll(position, playlists);
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send change playlist request. */
		try{
			this.protocol.sendChangePlaylistContainer(callback, playlistContainer, xml.toString());
		}
		catch(ProtocolException e){
			return false;
		}
		
		/* Get response. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Check confirmation. */
		PlaylistConfirmation confirmation = XMLPlaylistParser.parseConfirmation(data, "UTF-8");
		
		if(confirmation == null){
			return false;
		}
		
		/* Remove the playlist(s), since operation was successful. */
		for(int i = 0; i < playlists.size(); i++){
			playlistContainer.getPlaylists().remove(position);
		}
		
		/* Set new revision. */
		playlistContainer.setRevision(confirmation.getRevision());
		
		return true;
	}
	
	/**
	 * Get a playlist.
	 * 
	 * @param id     Id of the playlist to load.
	 * @param cached Whether to use a cached version if available or not.
	 * 
	 * @return A {@link Playlist} object or null on failure.
	 * 
	 * @see Playlist
	 */
	public Playlist playlist(String id, boolean cached){
		/* Data buffer. */
		byte[] data;
		
		if(cached && this.cache != null && this.cache.contains("playlist", id)){
			data = this.cache.load("playlist", id);
		}
		else{
			/* Create channel callback */
			ChannelCallback callback = new ChannelCallback();
			
			/* Send playlist request. */
			try{
				this.protocol.sendPlaylistRequest(callback, id);
			}
			catch(ProtocolException e){
				return null;
			}
			
			/* Get data. */
			data = callback.get(this.timeout, this.unit);
			
			/* Save data to cache. */
			if(this.cache != null){
				this.cache.store("playlist", id, data);
			}
		}
		
		/* Create and return playlist. */
		return XMLPlaylistParser.parsePlaylist(data, "UTF-8", id);
	}
	
	/**
	 * Get a playlist.
	 * 
	 * @param id Id of the playlist to load.
	 * 
	 * @return A {@link Playlist} object or null on failure.
	 * 
	 * @see Playlist
	 */
	public Playlist playlist(String id){
		return this.playlist(id, false);
	}
	
	/**
	 * Create a playlist.
	 * 
	 * @param name          The name of the playlist to create.
	 * @param collaborative If the playlist shall be collaborative.
	 * @param description   A description of the playlist.
	 * @param picture       An image id to associate with this playlist.
	 * 
	 * @return A {@link Playlist} object or null on failure.
	 * 
	 * @see Playlist
	 */
	public Playlist playlistCreate(String name, boolean collaborative, String description, String picture){
		String     id        = Hex.toHex(RandomBytes.randomBytes(16));
		String     user      = this.session.getUsername();
		long       timestamp = new Date().getTime() / 1000;
		Playlist   playlist  = new Playlist(id, name, user, collaborative);
		
		/* Create XML builder. */
		XMLBuilder xml = XMLBuilder.create()
			.element("id-is-unique").up()
			.element("change")
				.element("ops")
					.element("create").up()
					.element("name", name).up()
					.element("description", description).up()
					.element("picture", picture).up()
				.up()
				.element("time", "%d", timestamp).up()
				.element("user", user).up()
			.up()
			.element(
				"version", "0000000001,0000000000,0000000001,%d",
				collaborative?1:0
			).up();
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send change playlist request. */
		try{
			this.protocol.sendCreatePlaylist(callback, playlist, xml.toString());
		}
		catch(ProtocolException e){
			return null;
		}
		
		/* Get response. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Check confirmation. */
		PlaylistConfirmation confirmation = XMLPlaylistParser.parseConfirmation(data, "UTF-8");
		
		if(confirmation == null){
			return null;			
		}
		
		/* Set new revision and collaborative flag. */
		playlist.setRevision(confirmation.getRevision());
		playlist.setCollaborative(confirmation.isCollaborative());
		
		return playlist;
	}
	
	/**
	 * Create a playlist.
	 * 
	 * @param name The name of the playlist to create.
	 * 
	 * @return A {@link Playlist} object or null on failure.
	 * 
	 * @see Playlist
	 */
	public Playlist playlistCreate(String name){
		return this.playlistCreate(name, false, null, null);
	}
	
	/**
	 * Create a playlist from a given album.
	 * 
	 * @param sourceAlbum An {@link Album} object
	 * 
	 * @return A {@link Playlist} object or null on failure.
	 * 
	 * @see Playlist
	 * @see Album
	 */
	public Playlist playlistCreate(Album sourceAlbum){
		/* Browse album. */
		Album  album       = this.browse(sourceAlbum);
		String name        = String.format("%s - %s", album.getArtist().getName(), album.getName());
		String description = String.format("Released in %d", album.getYear());
		
		/* Create playlist from album. */
		Playlist playlist = this.playlistCreate(name, false, description, album.getCover());
		
		if(playlist != null && this.playlistAddTracks(playlist, album.getTracks(), 0)){
			return playlist;
		}
		else{
			this.playlistDestroy(playlist);
		}
		
		return playlist;
	}
	
	/**
	 * Destroy a playlist.
	 * 
	 * @param playlist The playlist to destroy.
	 * 
	 * @return true if the playlist was successfully destroyed, false otherwise.
	 * 
	 * @see Playlist
	 */
	public boolean playlistDestroy(Playlist playlist){
		String     user      = this.session.getUsername();
		long       timestamp = new Date().getTime() / 1000;
		
		/* Create XML builder. */
		XMLBuilder xml = XMLBuilder.create()
			.element("change")
				.element("ops")
					.element("destroy").up()
				.up()
				.element("time", "%d", timestamp).up()
				.element("user", user).up()
			.up()
			.element(
				"version", "%010d,%010d,%010d,%d",
				playlist.getRevision() + 1,
				playlist.getTracks().size(),
				playlist.getChecksum(),
				playlist.isCollaborative()?1:0
			).up();
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send change playlist request. */
		try{
			this.protocol.sendChangePlaylist(callback, playlist, xml.toString());
		}
		catch(ProtocolException e){
			return false;
		}
		
		/* Get response. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Check confirmation. */
		PlaylistConfirmation confirmation = XMLPlaylistParser.parseConfirmation(data, "UTF-8");
		
		if(confirmation == null){
			return false;			
		}
		
		return true;
	}
	
	/**
	 * Add a track to a playlist.
	 * 
	 * @param playlist The playlist.
	 * @param track    The track to be added.
	 * 
	 * @return true on success and false on failure.
	 */
	public boolean playlistAddTrack(Playlist playlist, Track track){
		return this.playlistAddTrack(playlist, track, playlist.getTracks().size());
	}
	
	/**
	 * Add a track to a playlist.
	 * 
	 * @param playlist The playlist.
	 * @param track    The track to be added.
	 * @param position The target position of the added track.
	 * 
	 * @return true on success and false on failure.
	 */
	public boolean playlistAddTrack(Playlist playlist, Track track, int position){
		List<Track> tracks = new ArrayList<Track>();
		
		tracks.add(track);
		
		return this.playlistAddTracks(playlist, tracks, position);
	}
	
	/**
	 * Add multiple tracks to a playlist.
	 * 
	 * @param playlist The playlist.
	 * @param tracks   A {@link List} of tracks to be added.
	 * @param position The target position of the added track.
	 * 
	 * @return true on success and false on failure.
	 */
	public boolean playlistAddTracks(Playlist playlist, List<Track> tracks, int position){
		String  user      = this.session.getUsername();
		long    timestamp = new Date().getTime() / 1000;
		
		/* Check if user is allowed to edit playlist. */
		if(!playlist.isCollaborative() && !playlist.getAuthor().equals(user)){
			return false;
		}
		
		/* Add the tracks for new checksum calculation. */
		playlist.getTracks().addAll(position, tracks);
		
		/* Build a comma separated list of tracks and append '01' to every id!. */
		String trackList = "";
		
		for(int i = 0; i < tracks.size(); i++){
			trackList += ((i > 0)?",":"") + tracks.get(i).getId() + "01";
		}
		
		/* Create XML builder. */
		XMLBuilder xml = XMLBuilder.create()
			.element("change")
				.element("ops")
					.element("add")
						.element("i", "%d", position).up()
						.element("items", trackList).up()
					.up()
				.up()
				.element("time", "%d", timestamp).up()
				.element("user", user).up()
			.up()
			.element(
				"version", "%010d,%010d,%010d,%d",
				playlist.getRevision() + 1,
				playlist.getTracks().size(),
				playlist.getChecksum(),
				playlist.isCollaborative()?1:0
			).up();
		
		/* Remove the tracks again. */
		for(int i = 0; i < tracks.size(); i++){
			playlist.getTracks().remove(position);
		}
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send change playlist request. */
		try{
			this.protocol.sendChangePlaylist(callback, playlist, xml.toString());
		}
		catch(ProtocolException e){
			return false;
		}
		
		/* Get response. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Check confirmation. */
		PlaylistConfirmation confirmation = XMLPlaylistParser.parseConfirmation(data, "UTF-8");
		
		if(confirmation == null){
			return false;
		}
		
		/* Add the tracks, since operation was successful. */
		playlist.getTracks().addAll(position, tracks);
		
		/* Set new revision and collaborative flag. */
		playlist.setRevision(confirmation.getRevision());
		playlist.setCollaborative(confirmation.isCollaborative());
		
		return true;
	}
	
	/**
	 * Remove a track from a playlist.
	 * 
	 * @param playlist The playlist.
	 * @param position The position of the track to remove.
	 * 
	 * @return true on success and false on failure.
	 */
	public boolean playlistRemoveTrack(Playlist playlist, int position){
		return this.playlistRemoveTracks(playlist, position, 1);
	}
	
	/**
	 * Remove multiple tracks from a playlist.
	 * 
	 * @param playlist The playlist.
	 * @param position The position of the tracks to remove.
	 * @param count    The number of track to remove.
	 * 
	 * @return true on success and false on failure.
	 */
	public boolean playlistRemoveTracks(Playlist playlist, int position, int count){
		String user      = this.session.getUsername();
		long   timestamp = new Date().getTime() / 1000;
		
		/* Check if user is allowed to edit playlist. */
		if(!playlist.isCollaborative() && !playlist.getAuthor().equals(user)){
			return false;
		}
		
		/* Create a sublist view (important!) and clone it by constructing a new ArrayList. */
		List<Track> tracks = new ArrayList<Track>(
			playlist.getTracks().subList(position, position + count)
		);
		
		/* First remove the track(s) to calculate the new checksum. */
		for(int i = 0; i < tracks.size(); i++){
			playlist.getTracks().remove(position);
		}
		
		/* Create XML builder. */
		XMLBuilder xml = XMLBuilder.create()
			.element("change")
				.element("ops")
					.element("del")
						.element("i", "%d", position).up()
						.element("k", "%d", count).up()
					.up()
				.up()
				.element("time", "%d", timestamp).up()
				.element("user", user).up()
			.up()
			.element(
				"version", "%010d,%010d,%010d,%d",
				playlist.getRevision() + 1,
				playlist.getTracks().size(),
				playlist.getChecksum(),
				playlist.isCollaborative()?1:0
			).up();
		
		/* Add the track(s) again, because we need the old checksum for sending. */
		playlist.getTracks().addAll(position, tracks);
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send change playlist request. */
		try{
			this.protocol.sendChangePlaylist(callback, playlist, xml.toString());
		}
		catch(ProtocolException e){
			return false;
		}
		
		/* Get response. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Check confirmation. */
		PlaylistConfirmation confirmation = XMLPlaylistParser.parseConfirmation(data, "UTF-8");
		
		if(confirmation == null){
			return false;
		}
		
		/* Remove the track(s), since operation was successful. */
		for(int i = 0; i < tracks.size(); i++){
			playlist.getTracks().remove(position);
		}
		
		/* Set new revision and collaborative flag. */
		playlist.setRevision(confirmation.getRevision());
		playlist.setCollaborative(confirmation.isCollaborative());
		
		return true;
	}
	
	// TODO: playlistMoveTrack(s) : <mov><i>6</i><j>2</j></mov>
	
	/**
	 * Rename a playlist.
	 * 
	 * @param playlist The {@link Playlist} to rename.
	 * @param name     The new name for the playlist.
	 * 
	 * @return true on success or false on failure.
	 * 
	 * @see Playlist
	 */
	public boolean playlistRename(Playlist playlist, String name){
		String user      = this.session.getUsername();
		long   timestamp = new Date().getTime() / 1000;
		
		/* Check if user is allowed to rename playlist. */
		if(!playlist.getAuthor().equals(user)){
			return false;
		}
		
		/* Create XML builder. */
		XMLBuilder xml = XMLBuilder.create()
			.element("change")
				.element("ops")
					.element("name", name).up()
				.up()
				.element("time", "%d", timestamp).up()
				.element("user", user).up()
			.up()
			.element(
				"version", "%010d,%010d,%010d,%d",
				playlist.getRevision() + 1,
				playlist.getTracks().size(),
				playlist.getChecksum(),
				playlist.isCollaborative()?1:0
			).up();
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send change playlist request. */
		try{
			this.protocol.sendChangePlaylist(callback, playlist, xml.toString());
		}
		catch(ProtocolException e){
			return false;
		}
		
		/* Get response. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Check confirmation. */
		PlaylistConfirmation confirmation = XMLPlaylistParser.parseConfirmation(data, "UTF-8");
		
		if(confirmation == null){
			return false;
		}
		
		/* Set name, since operation was successful. */
		playlist.setName(name);
		
		/* Set new revision and collaborative flag. */
		playlist.setRevision(confirmation.getRevision());
		playlist.setCollaborative(confirmation.isCollaborative());
		
		return true;
	}
	
	/**
	 * Set playlist collaboration.
	 * 
	 * @param playlist      The {@link Playlist} to change.
	 * @param collaborative Whether it should be collaborative or not.
	 * 
	 * @return true on success or false on failure.
	 * 
	 * @see Playlist
	 */
	public boolean playlistSetCollaborative(Playlist playlist, boolean collaborative){
		String user      = this.session.getUsername();
		long   timestamp = new Date().getTime();
		
		/* Check if user is allowed to set playlist collaboration. */
		if(!playlist.getAuthor().equals(user)){
			return false;
		}
		
		/* Create XML builder. */
		XMLBuilder xml = XMLBuilder.create()
			.element("change")
				.element("ops")
					.element("pub", "%d", collaborative).up()
				.up()
				.element("time", "%d", timestamp).up()
				.element("user", user).up()
			.up()
			.element(
				"version", "%010d,%010d,%010d,%d",
				playlist.getRevision() + 1,
				playlist.getTracks().size(),
				playlist.getChecksum(),
				playlist.isCollaborative()?1:0
			).up();
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send change playlist request. */
		try{
			this.protocol.sendChangePlaylist(callback, playlist, xml.toString());
		}
		catch(ProtocolException e){
			return false;
		}
		
		/* Get response. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Check confirmation. */
		PlaylistConfirmation confirmation = XMLPlaylistParser.parseConfirmation(data, "UTF-8");
		
		if(confirmation == null){
			return false;
		}
		
		/* Set new revision and collaborative flag. */
		playlist.setRevision(confirmation.getRevision());
		playlist.setCollaborative(confirmation.isCollaborative());
		
		return true;
	}
	
	/**
	 * Set playlist information.
	 * 
	 * @param playlist    The {@link Playlist} to change.
	 * @param description The description to set.
	 * @param picture     The picture to set.
	 * 
	 * @return true on success or false on failure.
	 * 
	 * @see Playlist
	 */
	public boolean playlistSetInformation(Playlist playlist, String description, String picture){
		String user      = this.session.getUsername();
		long   timestamp = new Date().getTime();
		
		/* Check if user is allowed to change playlist details. */
		if(!playlist.getAuthor().equals(user)){
			return false;
		}
		
		/* Create XML builder. */
		XMLBuilder xml = XMLBuilder.create()
			.element("change")
				.element("ops")
					.element("description", description).up()
					.element("picture", picture).up()
				.up()
				.element("time", "%d", timestamp).up()
				.element("user", user).up()
			.up()
			.element(
				"version", "%010d,%010d,%010d,%d",
				playlist.getRevision() + 1,
				playlist.getTracks().size(),
				playlist.getChecksum(),
				playlist.isCollaborative()?1:0
			).up();
		
		/* Create channel callback */
		ChannelCallback callback = new ChannelCallback();
		
		/* Send change playlist request. */
		try{
			this.protocol.sendChangePlaylist(callback, playlist, xml.toString());
		}
		catch(ProtocolException e){
			return false;
		}
		
		/* Get response. */
		byte[] data = callback.get(this.timeout, this.unit);
		
		/* Check confirmation. */
		PlaylistConfirmation confirmation = XMLPlaylistParser.parseConfirmation(data, "UTF-8");
		
		if(confirmation == null){
			return false;
		}
		
		/* Set metadata, since the operation was successful. */
		playlist.setDescription(description);
		playlist.setPicture(picture);
		
		/* Set new revision and collaborative flag. */
		playlist.setRevision(confirmation.getRevision());
		playlist.setCollaborative(confirmation.isCollaborative());
		
		return true;
	}
	
	/**
	 * Play a track in a background thread.
	 * 
	 * @param track    A {@link Track} object identifying the track to be played.
	 * @param listener A {@link PlaybackListener} receiving playback status updates.
	 */
	public void play(Track track, PlaybackListener listener){
		/* Create channel callbacks. */
		ChannelCallback       callback       = new ChannelCallback();
		ChannelHeaderCallback headerCallback = new ChannelHeaderCallback();
		
		/* Send play request (token notify + AES key). */
		try{
			this.protocol.sendPlayRequest(callback, track);
		}
		catch(ProtocolException e){
			return;
		}
		
		/* Get AES key. */
		byte[] key = callback.get(this.timeout, this.unit);
		
		/* Send header request to check for HTTP stream. */
		try{
			this.protocol.sendSubstreamRequest(headerCallback, track, 0, 0);
		}
		catch(ProtocolException e){
			return;
		}
		
		/* Get list of HTTP stream URLs. */
		List<String> urls = headerCallback.get(this.timeout, this.unit);
		
		/* If we got 4 HTTP stream URLs use them, otherwise use default channel streaming. */
		if(urls.size() == 4){
			this.player = new HTTPStreamPlayer(urls, track, key, listener);
			this.player.volume(this.volume);
		}
		else{
			this.player = new ChannelPlayer(this.protocol, track, key, listener);
			this.player.volume(this.volume);
		}
		
		/* Start playing. */
		this.play();
	}
	
	/**
	 * Start playing or resume current track.
	 */
	public void play(){
		if(this.player != null){
			this.player.play();
		}
	}
	
	/**
	 * Pause playback of current track.
	 */
	public void pause(){
		if(this.player != null){
			this.player.pause();
		}
	}
	
	/**
	 * Stop playback of current track.
	 */
	public void stop(){
		if(this.player != null){
			this.player.stop();
			
			this.player = null;
		}
	}
	
	/**
	 * Get length of current track.
	 * 
	 * @return Length in seconds or -1 if not available.
	 */
	public int length(){
		if(this.player != null){
			return this.player.length();
		}
		
		return -1;
	}
	
	/**
	 * Get playback position of current track.
	 * 
	 * @return Playback position in seconds or -1 if not available.
	 */
	public int position(){
		if(this.player != null){
			return this.player.position();
		}
		
		return -1;
	}
	
	/**
	 * Get volume.
	 * 
	 * @return A value from 0.0 to 1.0.
	 */
	public float volume(){
		if(this.player != null){
			return this.player.volume();
		}
		
		return -1;
	}
	
	/**
	 * Set volume.
	 * 
	 * @param volume A value from 0.0 to 1.0.
	 */
	public void volume(float volume){
		this.volume = volume;
		
		if(this.player != null){
			this.player.volume(this.volume);
		}
	}
	
	/**
	 * Handles incoming commands from the server.
	 * 
	 * @param command A command.
	 * @param payload Payload of packet.
	 */
	public void commandReceived(int command, byte[] payload){
		//System.out.format("< Command: 0x%02x Length: %d\n", command, payload.length);
		
		switch(command){
			case Command.COMMAND_SECRETBLK: {
				/* Check length. */
				if(payload.length != 336){
					System.err.format("Got command 0x02 with len %d, expected 336!\n", payload.length);
				}
				
				/* Check RSA public key. */
				byte[] rsaPublicKey = RSA.keyToBytes(this.session.getRSAPublicKey());
				
				for(int i = 0; i < 128; i++){
					if(payload[16 + i] != rsaPublicKey[i]){
						System.err.format("RSA public key doesn't match! %d\n", i);
						
						break;
					}
				}
				
				/* Send cache hash. */
				try{
					this.protocol.sendCacheHash();
				}
				catch(ProtocolException e){
					/* Just don't care. */
				}
				
				break;
			}
			case Command.COMMAND_PING: {
				/* Ignore the timestamp but respond to the request. */
				/* int timestamp = IntegerUtilities.bytesToInteger(payload); */
				try{
					this.protocol.sendPong();
				}
				catch(ProtocolException e){
					/* Just don't care. */
				}
				
				break;
			}
			case Command.COMMAND_PONGACK: {
				break;
			}
			case Command.COMMAND_CHANNELDATA: {
				Channel.process(payload);
				
				break;
			}
			case Command.COMMAND_CHANNELERR: {
				Channel.error(payload);
				
				break;
			}
			case Command.COMMAND_AESKEY: {
				/* Channel id is at offset 2. AES Key is at offset 4. */
				Channel.process(Arrays.copyOfRange(payload, 2, payload.length));
				
				break;
			}
			case Command.COMMAND_SHAHASH: {
				/* Do nothing. */
				break;
			}
			case Command.COMMAND_COUNTRYCODE: {
				//System.out.println("Country: " + new String(payload, Charset.forName("UTF-8")));
				this.user.setCountry(new String(payload, Charset.forName("UTF-8")));
				
				/* Release 'country' permit. */
				this.userSemaphore.release();
				
				break;
			}
			case Command.COMMAND_P2P_INITBLK: {
				/* Do nothing. */
				break;
			}
			case Command.COMMAND_NOTIFY: {
				/* HTML-notification, shown in a yellow bar in the official client. */
				/* Skip 11 byte header... */
				/*System.out.println("Notification: " + new String(
					Arrays.copyOfRange(payload, 11, payload.length), Charset.forName("UTF-8")
				));*/
				this.user.setNotification(new String(
					Arrays.copyOfRange(payload, 11, payload.length), Charset.forName("UTF-8")
				));
				
				break;
			}
			case Command.COMMAND_PRODINFO: {
				this.user = XMLUserParser.parseUser(payload, "UTF-8", this.user);
				
				/* Release 'prodinfo' permit. */
				this.userSemaphore.release();
				
				break;
			}
			case Command.COMMAND_WELCOME: {
				/* Request ads. */
				//this.protocol.sendAdRequest(new ChannelAdapter(), 0);
				//this.protocol.sendAdRequest(new ChannelAdapter(), 1);
				
				break;
			}
			case Command.COMMAND_PAUSE: {
				/* TODO: Show notification and pause. */
				
				break;
			}
			case Command.COMMAND_PLAYLISTCHANGED: {
				System.out.format("Playlist '%s' changed!\n", Hex.toHex(payload));
				
				break;
			}
			default: {
				System.out.format("Unknown Command: 0x%02x Length: %d\n", command, payload.length);
				System.out.println("Data: " + new String(payload) + " " + Hex.toHex(payload));
				
				break;
			}
		}
	}
	
	/**
	 * Main method for testing purposes.
	 * 
	 * @param args Commandline arguments.
	 * 
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		/* Create a spotify object. */
		JotifyConnection jotify = new JotifyConnection();
		jotify.setTimeout(1, TimeUnit.SECONDS);
		
		/* Create a scanner. */
		Scanner scanner = new Scanner(System.in);
		
		/* Current playlist. */
		Playlist playlist = new Playlist();
		
		/* Login. */
		while(true){
			System.out.print("Username: ");
			String username = scanner.nextLine();
			
			System.out.print("Password: ");
			String password = scanner.nextLine();
			
			try{
				jotify.login(username, password);
				
				System.out.println("Logged in! Type 'help' to see available commands.");
				
				break;
			}
			catch(AuthenticationException e){
				System.out.println("Invalid username and/or password! Try again.");
			}
		}
		
		/* Start packet IO in the background. */
		new Thread(jotify, "JotifyConnection-Thread").start();
		
		/* Print user info. */
		System.out.println(jotify.user());
		
		/* Check if user has a premium account. */
		if(!jotify.user.isPremium()){
			System.err.println("WARNING: You don't have a premium account! Jotify will NOT work!");
		}
		
		/* Wait for commands. */
		while(true){
			String   line     = scanner.nextLine();
			String[] parts    = line.split(" ", 2);
			String   command  = parts[0];
			String   argument = (parts.length > 1)?parts[1]:null;
			
			if(command.equals("search")){
				Result result = jotify.search(argument);

				playlist = Playlist.fromResult(result.getQuery(), "jotify", result);
				
				int i = 0;
				
				for(Track track : result.getTracks()){
					System.out.format(
						"%2d | %20s - %45s | %32s\n",
						i++,
						track.getArtist().getName(),
						track.getTitle(),
						track.getId()
					);
					
					if(i == 15){
						break;
					}
				}
			}
			else if(command.equals("toplist")){
				Result result = jotify.toplist(argument, null, null);
				
				playlist = Playlist.fromResult("toplist", "jotify", result);
				
				int i = 0;
				
				for(Artist artist : result.getArtists()){
					System.out.format(
						"%2d | %20s | %32s\n",
						i++,
						artist.getName(),
						artist.getId()
					);
					
					if(i == 15){
						break;
					}
				}
				
				for(Album album : result.getAlbums()){
					System.out.format(
						"%2d | %20s - %45s | %32s\n",
						i++,
						album.getArtist().getName(),
						album.getName(),
						album.getId()
					);
					
					if(i == 15){
						break;
					}
				}
				
				for(Track track : result.getTracks()){
					System.out.format(
						"%2d | %20s - %45s | %32s\n",
						i++,
						track.getArtist().getName(),
						track.getTitle(),
						track.getId()
					);
					
					if(i == 15){
						break;
					}
				}
			}
			else if(command.equals("play")){
				int position = Integer.parseInt(argument);
				
				if(position >= 0 && position < playlist.getTracks().size()){
					Track track = jotify.browse(playlist.getTracks().get(position));		
					
					System.out.format("Playing: %s - %s\n", track.getArtist().getName(), track.getTitle());
					
					jotify.stop();
					jotify.play(track, null);
				}
				else{
					System.out.format("Position %d not available!\n", position);
				}
			}
			else if(command.equals("help")){
				System.out.println("Available commands:");
				System.out.println("	search <query>");
				System.out.println("	toplist <type>");
				System.out.println("	play   <id>");
				System.out.println("	quit");
			}
			else if(command.equals("quit")){
				/* Stop playing. */
				jotify.stop();
				
				/* Close connection. */
				jotify.close();
				
				break;
			}
			else{
				System.out.println("Unrecognized command!");
			}
		}
	}
}
