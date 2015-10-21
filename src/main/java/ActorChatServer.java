import java.nio.ByteBuffer;
import java.io.IOException;
import java.net.InetSocketAddress;
import co.paralleluniverse.actors.*;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.io.*;

import java.util.*;

//Version of a Server Chat with Room Manager and Login Manager

public class ActorChatServer {

  static int MAXLEN = 1024;

  static enum Type {DATA, EOF, IOE, ENTER, LEAVE, LINE, CREATE, LOGIN, LOGIN_OK, LOGIN_ERROR, CREATE_OK, CREATE_ERROR, JOIN_ROOM, CREATE_ROOM, CHANGE_ROOM}

  //Message Room - Message used when an user has not a room 
  static class MRoom{
    final String name;
    final ActorRef user;

    public MRoom(String name, ActorRef user){
      this.name=name;
      this.user=user;
    }

  }

  //Message Romm Type 2- Message used when an user has a room 
  static class MRoom2{
    final String name;
    final ActorRef user;
    final ActorRef room;

    public MRoom2(String name, ActorRef user, ActorRef room){
      this.name=name;
      this.user=user;
      this.room=room;
    }

  }

  //Message Authentication - Used when we want to log or create account
  static class Auth{
  	final String user;
  	final String pass;
  	final ActorRef ref;

  	public Auth(String user, String pass, ActorRef ref){ 
  		this.user=user;
  		this.pass=pass;
  		this.ref=ref;

  	}

  }

  static class Msg {
    final Type type;
    final Object o;  // careful with mutable objects, such as the byte array
    Msg(Type type, Object o) { this.type = type; this.o = o; }
  }

static class LineReader extends BasicActor<Msg, Void> {

    final ActorRef<Msg> dest;
    final FiberSocketChannel socket;
    ByteBuffer in = ByteBuffer.allocate(MAXLEN);
    ByteBuffer out = ByteBuffer.allocate(MAXLEN);

    LineReader(ActorRef<Msg> dest, FiberSocketChannel socket) {
      this.dest = dest; this.socket = socket;
    }

    protected Void doRun() throws InterruptedException, SuspendExecution {
      boolean eof = false;
      byte b = 0;
      
      try {

        for(;;) { //blocking reader
          
          if (socket.read(in) <= 0) eof = true;
          in.flip();

          while(in.hasRemaining()) {
            b = in.get();
            out.put(b);
            if (b == '\n') break;
          }

          if (eof || b == '\n') { // send line
            out.flip();
            if (out.remaining() > 0) {
              byte[] ba = new byte[out.remaining()];
              out.get(ba);
              out.clear();
              dest.send(new Msg(Type.DATA, ba));
            }
          }

          if (eof && !in.hasRemaining()) break;
          in.compact();
        }

        dest.send(new Msg(Type.EOF, null));
        return null;

      } catch (IOException e) {
        dest.send(new Msg(Type.IOE, null));
        return null;
      }
    }  
}
    
    //Room Manager - Join an user into an existent room or create a new one
    static class RoomManager extends BasicActor<Msg, Void> {
  		final HashMap<String,ActorRef> rooms;

  		RoomManager(){
  			this.rooms= new HashMap<String,ActorRef>();
  		}

  		protected Void doRun() throws InterruptedException, SuspendExecution {
  			
        while(receive(msg -> { 
  				
  					switch(msg.type){

  					   case JOIN_ROOM:

                  MRoom mroom=(MRoom)msg.o;
                  if(!rooms.containsKey(mroom.name)){ActorRef nroom = new Room(mroom.name).spawn(); rooms.put(mroom.name,nroom); mroom.user.send(new Msg(Type.CREATE_ROOM,nroom));}
                  else mroom.user.send(new Msg(Type.CREATE_ROOM,rooms.get(mroom.name)));
                  return true;

              case CHANGE_ROOM:
                  MRoom2 mroom2=(MRoom2)msg.o;
                  if(!rooms.containsKey(mroom2.name)){ActorRef nroom = new Room(mroom2.name).spawn(); rooms.put(mroom2.name,nroom); mroom2.user.send(new Msg(Type.CREATE_ROOM,nroom)); mroom2.room.send(new Msg(Type.LEAVE,mroom2.user));}
                  else {mroom2.user.send(new Msg(Type.CREATE_ROOM,rooms.get(mroom2.name))); mroom2.room.send(new Msg(Type.LEAVE,mroom2.user));}
                  return true;
  					}
  					return false;
  				}));
  				return null;
  		}

  	}


  static class Room extends BasicActor<Msg, Void> {
    private Set<ActorRef> users = new HashSet();
    private String name;

    public Room(String name){this.name=name;}

    protected Void doRun() throws InterruptedException, SuspendExecution {

      while (receive(msg -> {
        switch (msg.type) {
          case ENTER:
            users.add((ActorRef)msg.o);
            return true;
          case LEAVE:
            users.remove((ActorRef)msg.o);
            return true;
          case LINE:
            for (ActorRef u : users) u.send(msg);
            return true;
        }
        return false;
      }));
      return null;
    }

  }

  //Login Manager - Create a new account or verify a login. We are checking if an account already exists when we are creating
  static class Login extends BasicActor<Msg, Void> {
  		final HashMap<String,String> logins;

  		Login(){
  			this.logins= new HashMap<String,String>();
  		}

  		protected Void doRun() throws InterruptedException, SuspendExecution {
  			while(receive(msg -> { 
  				
  					Auth data= (Auth)msg.o;

  					switch(msg.type){
  						case CREATE:
  							if(!logins.containsKey(data.user)){
  								logins.put(data.user, data.pass);
  								data.ref.send(new Msg(Type.CREATE_OK,msg.o));
  							}
  							else data.ref.send(new Msg(Type.CREATE_ERROR,msg.o));
  							return true;

  						case LOGIN:
  							if(logins.containsKey(data.user) && logins.get(data.user).equals(data.pass)){data.ref.send(new Msg(Type.LOGIN_OK,msg.o)); }

  							else {data.ref.send(new Msg(Type.LOGIN_ERROR,msg.o));}
  							return true;

  					}

  					return false;
  				}));
  				return null;
  		}

  	}

    static class User extends BasicActor<Msg, Void> {

    private ActorRef room;
    final FiberSocketChannel socket;
    final ActorRef login;
    private boolean ativo; 
    final ActorRef roomManager;
    private boolean hasRoom;

    User(FiberSocketChannel socket, ActorRef login, ActorRef roomManager) {this.socket = socket; this.ativo= false; this.login = login; this.roomManager=roomManager; this.hasRoom=false;}

    protected Void doRun() throws InterruptedException, SuspendExecution {

      new LineReader(self(), socket).spawn();

     //room.send(new Msg(Type.ENTER, self()));

      while (receive(msg -> {
        try {
        switch (msg.type) {

        	case DATA: //msg.o is a bytearray, we just case the different messages that we allow
          		String s= new String((byte[])msg.o);
          		String[] aux= s.split(" ");

          		switch(aux[0]){
          			case ":Login": login.send(new Msg(Type.LOGIN,new Auth(aux[1],aux[2],self())));
          			break;

          			case ":Create": login.send(new Msg(Type.CREATE,new Auth(aux[1],aux[2],self())));
          			break;

                case ":Room": if(ativo && !hasRoom)roomManager.send(new Msg(Type.JOIN_ROOM,new MRoom(aux[1],self())));
                              else if(ativo && hasRoom) roomManager.send(new Msg(Type.CHANGE_ROOM, new MRoom2(aux[1],self(),room)));
                              else System.out.println("You need to login or create a new account");
                break;

          			default:
          			if(ativo && hasRoom) room.send(new Msg(Type.LINE, msg.o));
          			else if(ativo && !hasRoom) System.out.println("Join a room"); 
                else System.out.println("Comands:\n:Login <user> <pass>\n:Create <user> <pass>\n:Room <name>");

          		}
        		  return true;

            case CREATE_ROOM:
              room=(ActorRef)msg.o;
              hasRoom=true;
              room.send(new Msg(Type.ENTER, self()));
              return true;


            case LOGIN_OK:
            	System.out.println("Login with sucess");
            	ativo=true;
            	return true;

            case LOGIN_ERROR:
            	System.out.println("Login error");
            	return true;

            case CREATE_OK:
            	System.out.println("Account created with sucess");
            	return true;

            case CREATE_ERROR:
            	System.out.println("User name already exists, try a new one");
            	return true;

          	case EOF:

          	case IOE:
            	room.send(new Msg(Type.LEAVE, self()));
            	socket.close();
            	return false;

            case LINE:
            	socket.write(ByteBuffer.wrap((byte[])msg.o));
            	return true;
        }
        } catch (IOException e) {
          room.send(new Msg(Type.LEAVE, self()));
        }
        return false;  // stops the actor if some unexpected message is received
      }));
      return null;
	}
  }

 static class Acceptor extends BasicActor {
    final int port;
    final ActorRef login;
    final ActorRef roomManager;

    Acceptor(int port, ActorRef login, ActorRef roomManager) { this.port = port; this.login=login; this.roomManager=roomManager;}

    protected Void doRun() throws InterruptedException, SuspendExecution {
      try {
      FiberServerSocketChannel ss = FiberServerSocketChannel.open();
      ss.bind(new InetSocketAddress(port));

      while (true) {
        FiberSocketChannel socket = ss.accept();
        new User(socket,login,roomManager).spawn();
      }
      } catch (IOException e) { }
      return null;
    }
  }



  public static void main(String[] args) throws Exception {
    int port = 12345; //Integer.parseInt(args[0]);

    ActorRef roomManager= new RoomManager().spawn();

    //String roomName="Global";
    //ActorRef room = new Room(roomName).spawn();


    ActorRef login = new Login().spawn();
    Acceptor acceptor = new Acceptor(port,login,roomManager);

    acceptor.spawn();
    acceptor.join();
  }

}

