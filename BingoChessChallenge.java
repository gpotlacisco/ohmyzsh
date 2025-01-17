import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;

import org.bson.Document;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import org.chernovia.lib.net.zugserv.*;
import org.chernovia.lichess.GameClient;
import org.chernovia.lichess.augh.*;
import org.chernovia.lichess.gson.GameData;
import org.chernovia.twitch.TwitchBot;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

public class BingoChessChallenge extends TwitchBot implements BingoListener, ConnListener {
	
	@WebSocket
	class LichessGameSock extends LiSock {
		Lichesser lich; //is this necessary?
		JsonNode lastPos = null;
		
		public LichessGameSock(String gid,Lichesser l) {	
			super(gid,l.handle,"wss://socket.lichess.org/" + gid + "/socket/v2", l.sock.getUpgrade()); 
			lich = l;
			String FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";
			JsonNode playNode = getGame().get("player"); JsonNode oppNode = getGame().get("opponent");
			if (playNode.get("color").asText().equals("white")) lastPos = gameToJson(FEN,
						playNode.get("user").get("username").asText(),playNode.get("rating").asInt(),999,
						oppNode.get("user").get("username").asText(),oppNode.get("rating").asInt(),999,true);
			else lastPos = gameToJson(FEN,
					oppNode.get("user").get("username").asText(),oppNode.get("rating").asInt(),999,
					playNode.get("user").get("username").asText(),playNode.get("rating").asInt(),999,true);
		}
	}

	class BingoServ extends WebSockServ {

		public BingoServ(int port, ConnListener l) { super(port, l); }
		
		public boolean isLegalName(String n) {
			if (n==null || n.length()<2 || n.length()>32) return false;
			else return true;
		}
	}
	
	class ChessPlayer extends BingoPlayer implements LiWatcher {
		LichessGameSock sock;
		String opponent;
				
		public ChessPlayer(LichessGameSock s, int f, BingoListener l) {
			super(s.lich.handle, f, l); 
			sock = s;  
			sock.addWatcher(this);
			sock.start();
			JsonNode g = sock.getGame();
			log("Initial Game State: " + g.toString());
			JsonNode p = g.get("player");
			JsonNode opp = g.get("opponent"); opponent = opp.get("user").get("id").asText().toLowerCase();
			JsonNode game = g.get("game");
			JsonNode clock = g.get("clock");
			if (p.get("color").asText().equals("white")) sock.lastPos = gameToJson(game.get("fen").asText(),
					p.get("user").get("username").asText(),p.get("rating").asInt(),clock.get("white").asInt(),
					opp.get("user").get("username").asText(),opp.get("rating").asInt(),clock.get("black").asInt(),
					true);
			else sock.lastPos = gameToJson(game.get("fen").asText(),
					opp.get("user").get("username").asText(),opp.get("rating").asInt(),clock.get("white").asInt(),
					p.get("user").get("username").asText(),p.get("rating").asInt(),clock.get("black").asInt(),
					true);
			log("Adding Observer to " + handle + ": " + opponent);
			for (Connection conn : getConns(opponent)) addObs(conn);
			//l.updateCard(this);
		}
		
		private void newMove(JsonNode data) {
			//log("New Move, Game Info: " + sock.getGame().toString());
			boolean wMove = data.get("ply").asInt() % 2 == 0;
			JsonNode clock = data.get("clock");
			double wTime = clock.get("white").asDouble(), bTime = clock.get("black").asDouble();
			String FEN = data.get("fen").asText();
			JsonNode gameObj = mapper.createObjectNode();
			for (Connection conn : getConns(handle)) {
				JsonNode playNode = sock.getGame().get("player"); JsonNode oppNode = sock.getGame().get("opponent");
				if (playNode.get("color").asText().equals("white")) gameObj = gameToJson(FEN,
							playNode.get("user").get("username").asText(),playNode.get("rating").asInt(),wTime,
							oppNode.get("user").get("username").asText(),oppNode.get("rating").asInt(),bTime,wMove);
				else gameObj = gameToJson(FEN,
						oppNode.get("user").get("username").asText(),oppNode.get("rating").asInt(),wTime,
						playNode.get("user").get("username").asText(),playNode.get("rating").asInt(),bTime,wMove);
				conn.tell("game", gameObj);
				sock.lastPos = gameObj;
			}
			String move = data.get("uci").asText();
			if (move.length() >= 4) {
				int x = move.charAt(2)-'a', y = 8-(move.charAt(3)-'0');
				checkMove(x, y);
			}
		}
		
		@Override
		public void lisock_msg(String message) {
			try {
				JsonNode node = mapper.readTree(message);
				JsonNode type = node.get("t");
				JsonNode data = node.get("d");
				//if (data != null) log(message); else return;
				if (type != null) switch (type.textValue()) {
					case "b": 
						for (int i=0; i < data.size(); i++) lisock_msg(data.get(i).toString());
						break;
					case "move": 
						log("New move for " + handle + ": " + data);
						this.newMove(data); 
						JsonNode winner = data.get("winner");
						if (winner != null && 
						winner.asText().equals(sock.getGame().get("player").get("color").asText())) {
							winner_chess(this);
						}
						break;
					case "end":
						log("End of game detected!");
						if (data != null) { 
							log("End of game: " + data.toString());
							if (data.asText().equals(sock.getGame().get("player").get("color").asText())) {
								winner_chess(this);
							}
						}
						sock.end(); //is this needed?
						break;
					default: 
						break;
				}
			} 
			catch (Exception e) { e.printStackTrace(); } 
		}

		@Override
		public void lisock_fin() {
			log("Sock done for player: " + handle);
			chessplayers.remove(sock.getGameId() + handle);
			for (Connection conn: getConns(handle)) {
				chessgames.remove(sock.getGame().get("game").get("id").asText());
				conn.tell("end_lichess_game","");
				updateAll(conn); 
			}
		}
	}
	
	class Lichesser extends Chatter implements LiWatcher {
		@WebSocket
		class ChallengeSock extends LiSock {
			String opponent, gid;
			public ChallengeSock(String id, String opp) {
				super(id,handle,"wss://socket.lichess.org/challenge/" + id + "/socket/v2",
				sock.getUpgrade());
				opponent = opp; 
			}
			public void mainLoop() throws InterruptedException {
				while(getSession() != null && getSession().isOpen()) {
					send(lichessDatagram("challenge",opponent).toString());
					sleep(1250);
				}
			}
		}
		LiSock sock;
		int wager = 10;
		HashMap<String,JsonNode> incomingChallenges;
		HashMap<String,ChallengeSock> outgoingChallenges;
		
		public Lichesser(LiSock s, String h) {
			super(h);
			incomingChallenges = new HashMap<String,JsonNode>();
			outgoingChallenges = new HashMap<String,ChallengeSock>();
			sock = s;
			sock.addWatcher(this);
			sock.start();
		}
		
		private void createChallenge(String opponent) {
			try {
				JsonNode challenge = sock.createChallenge(opponent, 1, true, 3, 0, "random"); //TODO: customize time
				if (challenge != null) {
					log("Creating New Challenge: " + challenge.toString());
					String id = challenge.get("challenge").get("id").asText();
					if (!outgoingChallenges.containsKey(id)) {
						ChallengeSock s = 
						new ChallengeSock(id,challenge.get("challenge").get("destUser").get("id").asText());
						outgoingChallenges.put(id,s);
						s.start();
					}
				}
			}
			catch (Exception augh) {
				for (Connection conn: getConns(handle)) conn.tell("error",augh.getMessage());
			}
		}
		
		public void clearOutgoingChallenges() {
			for (ChallengeSock s : outgoingChallenges.values()) s.end();
			outgoingChallenges.clear();
		}
		
		private void startGame(String gid, BingoListener l) {
			JsonNode playing = sock.finger().get("nowPlaying");
			log ("starting, gid: " + gid);
			log("starting, playing: " + playing);
			for (int i=0; i<playing.size();i++) {
				if (playing.get(i).get("gameId").asText().equals(gid)) {
					String opponent = playing.get(i).get("opponent").get("id").asText().toLowerCase(); //lowercase
					String color = playing.get(i).get("color").asText();
					ChessPlayer newPlayer = new ChessPlayer(
							new LichessGameSock(playing.get(i).get("fullId").asText(),this),wager,l);
					chessplayers.put(gid + handle.toLowerCase(),newPlayer);
					if (!chessgames.containsKey(gid)) {
						log("Creating game: " + handle + "-> " + opponent);
						if (color.equals("white")) {
							chessgames.put(gid,new LichessGame(gid,handle,opponent));
						}
						else chessgames.put(gid,new LichessGame(gid,opponent,handle.toLowerCase()));
					}
					incomingChallenges.clear();
					clearOutgoingChallenges();
					for (Connection conn : getConns(handle)) {
							conn.tell("begin_lichess_game", playing.get(i));
							conn.tell("game", newPlayer.sock.lastPos);
							updateChallenges(conn,this);
							//updateLichess(conn);
					}
					newPlayer.listener.updateCard(newPlayer);
					return;
				}
			}
		}

		public JsonNode toJson() {
			ObjectNode obj = mapper.createObjectNode();
			obj.put("handle", handle);
			obj.put("wager", wager);
			return obj;
		}
		
		@Override
		public void lisock_msg(String message) {
			try {
				JsonNode node = mapper.readTree(message);
				JsonNode type = node.get("t");
				JsonNode data = node.get("d");
				if (type != null) switch (type.textValue()) {
					case "b": 
						for (int i=0; i < data.size(); i++) lisock_msg(data.get(i).toString());
						break;
					case "challenges":
						incomingChallenges.clear();
						JsonNode in = data.get("in");
						if (in.size() > 0) {
							log("Challenge received as " + handle + ": " + data.toString());
							for (int i=0;i<in.size();i++) {
								incomingChallenges.put(in.get(i).get("id").asText(),in.get(i)); 
							}
							for (Connection conn : getConns(handle)) updateChallenges(conn,this);
						}
						break; 
					default: 
						break;
				}
			} 
			catch (Exception e) { e.printStackTrace(); } 
		}

		@Override
		public void lisock_fin() {
			log("Logged out: " + handle);
			clearOutgoingChallenges();
			lichs.remove(handle);
			for (Connection conn : getConns(handle)) conn.tell("lichess_logout", ""); 
			for (Connection c : serv.getAllConnections(true)) updateLichess(c);
		}
	}
	
	class LichessGame {
		String gid;
		String white;
		String black;
		boolean started;
		public LichessGame(String id, String w,String b) {
			gid = id; white = w; black = b; started = false;
		}
		public ChessPlayer getPlayer(String name) {
			ChessPlayer player = chessplayers.get(gid + white);
			if (player != null && player.handle.equalsIgnoreCase(name)) return player;
			else {
				player = chessplayers.get(gid + black);
				if (player != null && player.handle.equalsIgnoreCase(name)) return player;
			}
			return null;
		}
	}
	
	ObjectMapper mapper = new ObjectMapper();
	String gameType = "blitz";
	MongoCollection<Document> playData;
	String bingoURL;
	BingoServ serv;
	GameClient tv_game;
	GameData tv_data;
	Chatter announcer;
	HashMap<String,LichessGame> chessgames;
	HashMap<String,ChessPlayer> chessplayers;
	HashMap<String,Lichesser> lichs;
	String lastFEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";
	JsonNode lastBingoPos = null;
	long lastMoveTime;
			
	public static void main(String[] args) {
		WebSockConn.VERBOSITY = 0;
		new BingoChessChallenge(args);
		//bingo.newPlayer("Zugx1"); bingo.newPlayer("Zugx2"); bingo.newPlayer("Zugx3");
	}
	
	public BingoChessChallenge(String[] args) {
		announcer = new Chatter(args[0]);
		lichs = new HashMap<String,Lichesser>();
		chessplayers = new HashMap<String,ChessPlayer>();
		chessgames = new HashMap<String,LichessGame>();
		BingoPlayer.SQUARE_BAG = new Vector<Dimension>();
		for (int x=0;x<8;x++)
		for (int y=0;y<8;y++)
		BingoPlayer.SQUARE_BAG.add(new Dimension(x,y));
		initIRC(args[0], args[1], args[2], args[3]);
		loadAdmins("res/admins.txt");
		serv = new BingoServ(Integer.parseInt(args[4]),this);
		serv.startSrv();
		bingoURL = args[5];
		MongoClientURI connStr = new MongoClientURI("mongodb://bingobot:" + args[6] + "@localhost:27017/BingoBase");
		MongoClient mongoClient = new MongoClient(connStr);
		MongoDatabase bingoBase = mongoClient.getDatabase("BingoBase");
		playData = bingoBase.getCollection("players");
	}
	
	private ArrayList<ChessPlayer> getPlayerlist(String handle) {
		ArrayList<ChessPlayer> list = new ArrayList<ChessPlayer>();
		for (LichessGame game : chessgames.values()) {
			ChessPlayer player = game.getPlayer(handle); if (player != null) list.add(player);
		}
		return list;
	}
	
	private ChessPlayer getChessPlayer(String handle) {
		ArrayList<ChessPlayer> playlist = getPlayerlist(handle);
		if (!playlist.isEmpty()) return playlist.get(0); //TODO: multiple games?
		else return null;
	}

	private JsonNode gameToJson(
		String FEN, String w, int w_rat, double w_time, String b, int b_rat, double b_time, boolean w_move) {
		ObjectNode gameObj = mapper.createObjectNode(); 
		gameObj.put("fen",FEN);
		gameObj.put("white", w);
		gameObj.put("white_rating", w_rat);
		gameObj.put("white_time", w_time);
		gameObj.put("black", b);
		gameObj.put("black_rating", b_rat);
		gameObj.put("black_time", b_time);
		gameObj.put("white_move", w_move);
		return gameObj;
	}
	
	private ArrayList<Connection> getConns(String handle) {
		ArrayList<Connection> connections = new ArrayList<Connection>();
		for (Connection c : serv.getAllConnections(true)) {
			if (c.getHandle().equalsIgnoreCase(handle)) connections.add(c);
		}
		return connections;
	}
	
	public void buyCard(String handle, int fee) {
		if (playData.find(eq("name", handle)).first() == null) {
			playData.insertOne(new Document().append("name", handle).append("gold",100).append("games", 1));
		}
		else {
			playData.updateOne(eq("name",handle),inc("games",1));
			playData.updateOne(eq("name",handle),inc("gold",-fee));
		}
		whisper(handle,"You have bought a new card.");
	}
	
	private JsonNode lichessDatagram(String type, String data) {
		ObjectNode obj = mapper.createObjectNode();
		obj.put("t", type);
		obj.put("d", data);
		return obj;
	}
	
	private void winner_chess(ChessPlayer winner) {
		int winnings = winner.fee * 2;
		ctell(winner.handle + " wins " + winnings + "gold!");
		playData.updateOne(eq("name",winner.handle),inc("gold",winnings));
	}
	
	private void giveFinger(Connection conn) {
		Document playDoc = playData.find(eq("name",conn.getHandle())).first();
		if (playDoc != null) {
			ObjectNode obj = mapper.createObjectNode();
			obj.put("gold",playDoc.getInteger("gold").toString());
			obj.put("games",playDoc.getInteger("games").toString());
			conn.tell("finger", obj);
		}
	}
	
	private void updateAll(Connection conn) { update(conn,true,true,true,true,true); }
	private void update(Connection conn,
	boolean tv,boolean card,boolean chess,boolean lichess,boolean challenges) {
		ChessPlayer player = card || chess ? getChessPlayer(conn.getHandle()) : null;
		if (player != null) {
			if (card) player.listener.updateCard(player);
			if (chess) updateChess(conn,player);
		}
		if (tv) updateTV(conn,lastBingoPos);
		if (lichess) updateLichess(conn);
		if (challenges) updateChallenges(conn,getLich(conn.getHandle()));
	}
	
	private void updateLichess(Connection conn) {
		ObjectNode obj = mapper.createObjectNode();
		ArrayNode logins = mapper.createArrayNode();
		for (Lichesser lich : lichs.values()) {
			if (!lich.handle.equalsIgnoreCase(conn.getHandle())) logins.add(lich.toJson());
		}
		obj.set("logins", logins); 
		//JsonArray gamelist = new JsonArray();
		//for (ChessGame g: games) gamelist.add(g.toJson());
		//obj.add("games", gamelist);
		conn.tell("lichess",obj);
	}
	
	private void updateChallenges(Connection conn, Lichesser lich) {
		if (lich != null) {
			ArrayNode array = mapper.createArrayNode();
			for (JsonNode c : lich.incomingChallenges.values()) array.add(c);
			conn.tell("challenges",array);
		}
	}
	
	private void updateChess(Connection conn, ChessPlayer player) {
		if (player != null) conn.tell("game", player.sock.lastPos);
	}
	
	private void updateTV(Connection conn, JsonNode position) {
		if (position != null) conn.tell("tv_game", position);
	}
	
	private Lichesser getLich(String handle) { return lichs.get(handle.toLowerCase()); }
	
	private void newLichessLogin(Connection conn, String user, String pwd) {
		if (getLich(conn.getHandle()) != null) {
			conn.tell("error", "Already logged in!"); return;
		}
		LiSock sock = new LiSock("wss://socket.lichess.org/socket",user,pwd);
		if (sock.logged_in()) {
			log("Logged in " + user + "...");
			lichs.put(conn.getHandle().toLowerCase(),new Lichesser(sock,conn.getHandle()));
			conn.tell("lichess_login_ok", conn.getHandle());
			for (Connection c : serv.getAllConnections(true)) updateLichess(c);
		}
		else conn.tell("lichess_login_fail", "Couldn't log in to lichess!");
	}
	
	@Override
	public void handleMsg(String chan, String sender, String msg, boolean whisper) {
		if (!whisper) ctell(sender,msg,false);
	}

	public void ctell(String msg) { ctell(null,msg,false); }
	public void ctell(String sender, String msg, boolean echo) {
		Chatter chatter;
		if (sender == null) chatter = announcer;
		else chatter = getLich(sender);
		if (chatter != null) {
			ObjectNode obj = mapper.createObjectNode();
			obj.set("chatter",chatter.chatterToJSON());
			obj.put("msg", msg);
			for (Connection conn : serv.getAllConnections(true)) conn.tell("ctell",obj);
			if (echo) tch(sender + ": " + msg);
		}
	}
	
	@Override
	public void adminCmd(String cmd, boolean whisper) { 
		String[] tokens = cmd.split(" ");
		switch (tokens.length) {
			case 1:
				if (tokens[0].equalsIgnoreCase("EXIT")) System.exit(-1);
				break;
			default:
				break;
		}
	}
	
	private boolean handleGeneralCmd(Connection conn, String cmd, JsonNode data) {
		if (cmd.equalsIgnoreCase("UPDATE")) updateAll(conn);
		else if (cmd.equalsIgnoreCase("FINGER")) giveFinger(conn);
		else if (cmd.equalsIgnoreCase("TELL_CHAN")) {
			ctell(conn.getHandle(),data.asText(),true);
		}
		else if (cmd.equalsIgnoreCase("LICHESS_LOGIN")) {
			newLichessLogin(conn,data.get("frank").asText(), data.get("zappa").asText());
		}
		else return false;
		return true;
	}
	
	private boolean handleBingoCmd(Connection conn, ChessPlayer chessplayer, String cmd) {
		if (cmd.equalsIgnoreCase("BINGO")) { 
			if (chessplayer == null) {
				ctell(conn.getHandle() + " isn't even playing!  What a doofus.");
			}
			else if (chessplayer.checkBingo()) {
					ChessPlayer loser = chessplayers.get(
					chessplayer.sock.getGame().get("game").get("id").asText() + chessplayer.opponent); 
					if (loser != null) {
						ctell(chessplayer.handle + " bingos " + loser.handle + "!");
						loser.sock.send(lichessDatagram("resign","").toString());
					}
					else log("ERROR: Opponent not found: " + loser);
			}
			else {	
				ctell(chessplayer.handle + " made a false bingo claim! Sad.");
			}
		}
		else return false;
		return true;
	}
	
	private boolean handleChessgameCmd(Connection conn, ChessPlayer chessplayer, String cmd, JsonNode data) {
		if (cmd.equalsIgnoreCase("MOVE")) {
			if (chessplayer != null) {
				try {
					ObjectNode obj = mapper.createObjectNode();
					obj.put("t", "move");
					ObjectNode move = mapper.createObjectNode();
					move.put("from", data.get("from").asText());
					move.put("to", data.get("to").asText());
					obj.set("d", move);
					chessplayer.sock.send(obj.toString());
				}
				catch (Exception wtf) { conn.tell("error", wtf.getMessage()); };
			}
			else conn.tell("error", "Not playing!");
		}
		else if (cmd.equalsIgnoreCase("ABORT")) {
			if (chessplayer != null) {
				chessplayer.sock.send(lichessDatagram("abort","").toString());
			}
			else conn.tell("error", "Not playing!");
		}
		else return false;
		return true;
	}
	
	private boolean handleLichessCmd(Connection conn, Lichesser lich, ChessPlayer chessplayer, 
	String cmd, JsonNode data) {
		//log("Lichess command from " + lich + "," + playlist.size());
		if (cmd.equalsIgnoreCase("ACCEPT")) {
			if (lich == null) conn.tell("error", "Not logged in!");
			else if (chessplayer != null) conn.tell("error", "Already playing!");
			else {
				JsonNode newgame = lich.sock.acceptChallenge(data.asText());
				if (newgame == null || newgame.asText().equals("404")) conn.tell("error", "No such game");
				else if (newgame.get("error") != null) 	conn.tell("error", newgame.get("error").asText());
				else {
					log("Challenge accepted: " + newgame);
					Lichesser player = getLich(newgame.get("player").get("user").get("id").asText());
					Lichesser opp = getLich(newgame.get("opponent").get("user").get("id").asText());
					if (player != null) player.startGame(newgame.get("game").get("id").asText(),this);
					if (opp != null) opp.startGame(newgame.get("game").get("id").asText(),this);
				}
			}
		}
		else if (cmd.equalsIgnoreCase("CHALLENGE")) {
			if (lich == null) conn.tell("error", "Not logged in!");
			else if (chessplayer != null) conn.tell("error", "Already playing!");
			else lich.createChallenge(data.asText());
		}
		else if (cmd.equalsIgnoreCase("CANCEL")) {
			if (lich == null) conn.tell("error", "Not logged in!");
			else lich.clearOutgoingChallenges();
		}
		else return false;
		return true;
	}

	@Override
	public void newMsg(Connection conn, String msg) {
		JsonNode node = null;
		try { node = mapper.readTree(msg); } 
		catch (JsonProcessingException e) { e.printStackTrace(); return; } 
		catch (IOException e) { e.printStackTrace(); return; }
		String cmd = node.get("cmd").asText();
		JsonNode data = node.get("data");
		
		log("CMD: " + cmd + ", from " + conn.getHandle());
		
		//log("general cmd...");
		if (handleGeneralCmd(conn,cmd,data)) return;
		//log("chess cmd...");
		ChessPlayer chessplayer = getChessPlayer(conn.getHandle());
		if (handleChessgameCmd(conn,chessplayer,cmd,data)) return;
		//log("bingo cmd...");
		if (handleBingoCmd(conn,chessplayer,cmd)) return;
		//log("lichess cmd...");
		if (handleLichessCmd(conn,getLich(conn.getHandle()),chessplayer,cmd,data)) return;
		
		log("Error: unknown command: " + cmd);
	}
	
	@Override
	public void loggedIn(Connection conn) {
		//conn.setHandle(conn.getHandle().toLowerCase());
		updateAll(conn);
	}

	@Override
	public void disconnected(Connection conn) {
		//for (Player player : players.values()) player.removeObs(conn);
		Lichesser l = getLich(conn.getHandle()); if (l != null) l.sock.end(); 
		ChessPlayer p = getChessPlayer(conn.getHandle()); if (p != null) p.sock.end();
	}

	@Override
	public void updateCard(BingoPlayer bingoer) {
		for (Connection c : getConns(bingoer.handle)) c.tell("card", bingoer.toJSON());
		for (Connection c : bingoer.observers) c.tell("card", bingoer.toJSON());
	}
}