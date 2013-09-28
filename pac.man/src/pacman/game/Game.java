package pacman.game;

import java.util.BitSet;
import java.util.EnumMap;
import java.util.Random;
import java.util.Map.Entry;
import pacman.game.Constants.DM;
import pacman.game.Constants.MOVE;
import pacman.game.internal.Ghost;
import pacman.game.internal.Laberinto;
import pacman.game.internal.Node;
import pacman.game.internal.PacMan;
import pacman.game.internal.PathsCache;

import static pacman.game.Constants.*;

/**
 * The implementation of Ms Pac-Man. This class contains the game engine and all methods required to
 * query the state of the game. First, the mazes are loaded once only as they are immutable. The game
 * then proceeds to initialise all variables using default values. The game class also provides numerous
 * methods to extract the game state as a string (used for replays and for communication via pipes during
 * the competition) and to create copies. Care has been taken to implement the game efficiently to ensure
 * that copies can be created quickly.
 * 
 * The game has a central update method called advanceGame which takes a move for Ms Pac-Man and up to
 * 4 moves for the ghosts. It then updates the positions of all characters, check whether pills or power
 * pills have been eaten and updates the game state accordingly.
 * 
 * All other methods are to access the gamestate and to compute numerous aspects such as directions to taken
 * given a target or a shortest path from a to b. All shortest path distances from any node to any other node
 * are pre-computed and loaded from file. This makes these methods more efficient. Note about the ghosts: ghosts
 * are not allowed to reverse. Hence it is not possible to simply look up the shortest path distance. Instead,
 * one can approximate this greedily or use A* to compute it properly. The former is somewhat quicker and has
 * a low error rate. The latter takes a bit longer but is absolutely accurate. We use the pre-computed shortest
 * path distances as admissable heuristic so it is very efficient.
 */
public final class Game
{
	//pills stored as bitsets for efficient copying
	private BitSet pills, powerPills;
	//all the game's variables
	private int indiceDeLaberinto, cuentaElLvl, tiempoLvlActual, tiempoTotal, score, fastamasComerMultiplicador, timeOfLastGlobalReversal;	
	private boolean juegoTerminado, pacmanFueComido, pastillaFueComida, pildoraPoderFueComida;
	private EnumMap<GHOST,Boolean> fantasmaComido;
	//the data relating to pacman and the ghosts are stored in respective data structures for clarity
	private PacMan pacman;
	private EnumMap<GHOST, Ghost> fantasmas;

	//mazes are only loaded once since they don't change over time
	private static Laberinto[] laberintos=new Laberinto[NUM_LABERINTOS];;
	
	private Laberinto laberintoActua;
	
	static 
	{
		for(int i=0;i<laberintos.length;i++)
			laberintos[i]=new Laberinto(i);
	}
	
	public static PathsCache[] caches=new PathsCache[NUM_LABERINTOS];
	
	static 
	{
		for(int i=0;i<laberintos.length;i++)
		{
			caches[i]=new PathsCache(i);
		}
	}
	
	private Random rnd;
	private long seed;	
	
	/////////////////////////////////////////////////////////////////////////////
	///////////////////  Constructors and initialisers  /////////////////////////
	/////////////////////////////////////////////////////////////////////////////
	
	 /**
	 * Crea una instancia de un nuevo juego. La semilla se utiliza para inicializar el pseudo-aleatoria
	 * Generador de números. De esta manera, un juego puede ser replicado exactamente mediante el uso de idéntica
	 * Semillas. Nota: en la competencia, los juegos recibidos desde el servidor de juego son
	 * El uso de diferentes semillas. De lo contrario acontecimientos inversión global sería predecible.
	 *
	 * @param seed The seed for the pseudo-random number generator
	 */
	public Game(long seed)
	{		
		this.seed=seed;
		rnd=new Random(seed);
		
		_init(0);
	}
	
	/**
	 * Initiates a new game specifying the maze to start with.
	 * 
	 * @param seed Seed used for the pseudo-random numbers
	 * @param initialMaze The maze to start the game with
	 */
	public Game(long seed,int initialMaze)
	{						
		this.seed=seed;
		rnd=new Random(seed);
		
		_init(initialMaze);		
	}
	
	/**
	 * Empty constructor used by the copy method.
	 */
	private Game(){}
	
	/**
	 * _init.
	 *
	 * @param initialMaze the initial maze
	 */
	private void _init(int initialMaze)
	{
		indiceDeLaberinto=initialMaze;
		score=tiempoLvlActual=cuentaElLvl=tiempoTotal=0;
		fastamasComerMultiplicador=1;
		juegoTerminado=false;
		timeOfLastGlobalReversal=-1;		
		pacmanFueComido=false;
		pastillaFueComida=false;
		pildoraPoderFueComida=false;
		
		fantasmaComido=new EnumMap<GHOST,Boolean>(GHOST.class);
		
		for(GHOST ghost : GHOST.values())
			fantasmaComido.put(ghost,false);
		
		_setPills(laberintoActua=laberintos[indiceDeLaberinto]);
		_initGhosts();
		
		pacman=new PacMan(laberintoActua.initialPacManNodeIndex,MOVE.LEFT,NUM_LIVES,false);		
	}
	
	/**
	 * _new level reset.
	 */
	private void _newLevelReset()
	{
		indiceDeLaberinto=++indiceDeLaberinto%NUM_LABERINTOS;
		cuentaElLvl++;
		laberintoActua=laberintos[indiceDeLaberinto];
		
		tiempoLvlActual=0;
		fastamasComerMultiplicador=1;
			
		_setPills(laberintoActua);
		_levelReset();
	}
	
	/**
	 * _level reset.
	 */
	private void _levelReset()
	{
		fastamasComerMultiplicador=1;
		
		_initGhosts();
		
		pacman.currentNodeIndex=laberintoActua.initialPacManNodeIndex;
		pacman.lastMoveMade=MOVE.LEFT;
	}
	
	/**
	 * _set pills.
	 *
	 * @param maze the maze
	 */
	private void _setPills(Laberinto maze)
	{
		pills=new BitSet(laberintoActua.pillIndices.length);
		pills.set(0,laberintoActua.pillIndices.length);
		powerPills=new BitSet(laberintoActua.powerPillIndices.length);
		powerPills.set(0,laberintoActua.powerPillIndices.length);
	}
	
	/**
	 * _init ghosts.
	 */
	private void _initGhosts()
	{
		fantasmas=new EnumMap<GHOST, Ghost>(GHOST.class);
					
		for(GHOST ghostType : GHOST.values())
			fantasmas.put(ghostType,new Ghost(ghostType,laberintoActua.lairNodeIndex,0,
					(int)(ghostType.initialLairTime*(Math.pow(LAIR_REDUCTION,cuentaElLvl%LEVEL_RESET_REDUCTION))),MOVE.NEUTRAL));
	}

	/**
	 * Gets the game state as a string: all variables are written to a string in a pre-determined
	 * order. The string may later be used to recreate a game state using the setGameState() method.
	 *
	 * Variables not included: enableGlobalReversals
	 *
	 * @return The game state as a string
	 */
	public String getGameState()
	{
		StringBuilder sb=new StringBuilder();

		sb.append(indiceDeLaberinto+","+tiempoTotal+","+score+","+tiempoLvlActual+","+cuentaElLvl+","
				+pacman.currentNodeIndex+","+pacman.lastMoveMade+","+pacman.numberOfLivesRemaining+","+pacman.hasReceivedExtraLife+",");

		for(Ghost ghost : fantasmas.values())
			sb.append(ghost.currentNodeIndex+","+ghost.edibleTime+","+ghost.lairTime+","+ghost.lastMoveMade+",");

		for(int i=0;i<laberintoActua.pillIndices.length;i++)
			if(pills.get(i))
				sb.append("1");
			else
				sb.append("0");

		sb.append(",");
		
		for(int i=0;i<laberintoActua.powerPillIndices.length;i++)
			if(powerPills.get(i))
				sb.append("1");
			else
				sb.append("0");
		
		sb.append(",");		
		sb.append(timeOfLastGlobalReversal);
		sb.append(",");		
		sb.append(pacmanFueComido);
		sb.append(",");	
		
		for(GHOST ghost : GHOST.values())
		{
			sb.append(fantasmaComido.get(ghost));
			sb.append(",");
		}
		
		sb.append(pastillaFueComida);
		sb.append(",");
		sb.append(pildoraPoderFueComida);		
		
		return sb.toString();
	}
	
	/**
	 * Sets the game state from a string: the inverse of getGameState(). It reconstructs
	 * all the game's variables from the string.
	 *
	 * @param gameState The game state represented as a string
	 */
	public void setGameState(String gameState)
	{	
		String[] values=gameState.split(",");
		
		int index=0;
		
		indiceDeLaberinto=Integer.parseInt(values[index++]);
		tiempoTotal=Integer.parseInt(values[index++]);
		score=Integer.parseInt(values[index++]);
		tiempoLvlActual=Integer.parseInt(values[index++]);
		cuentaElLvl=Integer.parseInt(values[index++]);
		
		pacman=new PacMan(Integer.parseInt(values[index++]),MOVE.valueOf(values[index++]),
				Integer.parseInt(values[index++]),Boolean.parseBoolean(values[index++]));
		
		fantasmas=new EnumMap<GHOST, Ghost>(GHOST.class);
		
		for(GHOST ghostType : GHOST.values())
			fantasmas.put(ghostType,new Ghost(ghostType,Integer.parseInt(values[index++]),Integer.parseInt(values[index++]),
					Integer.parseInt(values[index++]),MOVE.valueOf(values[index++])));
			
		_setPills(laberintoActua=laberintos[indiceDeLaberinto]);
		
		for(int i=0;i<values[index].length();i++)
			if(values[index].charAt(i)=='1')
				pills.set(i);
			else
				pills.clear(i);
		
		index++;
		
		for(int i=0;i<values[index].length();i++)
			if(values[index].charAt(i)=='1')
				powerPills.set(i);		
			else
				powerPills.clear(i);
		
		timeOfLastGlobalReversal=Integer.parseInt(values[++index]);			
		pacmanFueComido=Boolean.parseBoolean(values[++index]);
		
		fantasmaComido=new EnumMap<GHOST,Boolean>(GHOST.class);
		
		for(GHOST ghost : GHOST.values())
			fantasmaComido.put(ghost,Boolean.parseBoolean(values[++index]));
		
		pastillaFueComida=Boolean.parseBoolean(values[++index]);
		pildoraPoderFueComida=Boolean.parseBoolean(values[++index]);
	}
	
	/**
	 * Returns an exact copy of the game. This may be used for forward searches
	 * such as minimax. The copying is relatively efficient.
	 *
	 * @return the game
	 */
	public Game copy()
	{
		Game copy=new Game();
			
		copy.seed=seed;
		copy.rnd=new Random(seed);
		copy.laberintoActua=laberintoActua;		
		copy.pills=(BitSet)pills.clone();
		copy.powerPills=(BitSet)powerPills.clone();		
		copy.indiceDeLaberinto=indiceDeLaberinto;
		copy.cuentaElLvl=cuentaElLvl;
		copy.tiempoLvlActual=tiempoLvlActual;		
		copy.tiempoTotal=tiempoTotal;
		copy.score=score;
		copy.fastamasComerMultiplicador=fastamasComerMultiplicador;
		copy.juegoTerminado=juegoTerminado;
		copy.timeOfLastGlobalReversal=timeOfLastGlobalReversal;		
		copy.pacmanFueComido=pacmanFueComido;
		copy.pastillaFueComida=pastillaFueComida;
		copy.pildoraPoderFueComida=pildoraPoderFueComida;		
		copy.pacman=pacman.copy();
		
		copy.fantasmaComido=new EnumMap<GHOST,Boolean>(GHOST.class);
		copy.fantasmas=new EnumMap<GHOST,Ghost>(GHOST.class);
		
		for(GHOST ghostType : GHOST.values())
		{
			copy.fantasmas.put(ghostType,fantasmas.get(ghostType).copy());
			copy.fantasmaComido.put(ghostType,fantasmaComido.get(ghostType));
		}
			
		return copy;	
	}

	/////////////////////////////////////////////////////////////////////////////
	///////////////////////////  Game-engine   //////////////////////////////////
	/////////////////////////////////////////////////////////////////////////////

	/**
	 * Central method that advances the game state using the moves supplied by
	 * the controllers. It first updates Ms Pac-Man, then the ghosts and then
	 * the general game logic.
	 *
	 * @param pacManMove The move supplied by the Ms Pac-Man controller
	 * @param ghostMoves The moves supplied by the ghosts controller
	 */	
	public void advanceGame(MOVE pacManMove,EnumMap<GHOST,MOVE> ghostMoves)
	{		
		updatePacMan(pacManMove);
		updateGhosts(ghostMoves);	
		updateGame();
	}
	
	public void advanceGameWithoutReverse(MOVE pacManMove,EnumMap<GHOST,MOVE> ghostMoves)
	{		
		updatePacMan(pacManMove);
		updateGhostsWithoutReverse(ghostMoves);
		updateGame();
	}
	
	public void advanceGameWithForcedReverse(MOVE pacManMove,EnumMap<GHOST,MOVE> ghostMoves)
	{		
		updatePacMan(pacManMove);
		updateGhostsWithForcedReverse(ghostMoves);
		updateGame();
	}
	
	public void advanceGameWithPowerPillReverseOnly(MOVE pacManMove,EnumMap<GHOST,MOVE> ghostMoves)
	{		
		updatePacMan(pacManMove);
		
		if(pildoraPoderFueComida)
			updateGhostsWithForcedReverse(ghostMoves);
		else
			updateGhostsWithoutReverse(ghostMoves);
		
		updateGame();
	}
		
	/**
	 * Updates the state of Ms Pac-Man given the move returned by the controller.
	 *
	 * @param pacManMove The move supplied by the Ms Pac-Man controller
	 */
	public void updatePacMan(MOVE pacManMove)
	{
		_updatePacMan(pacManMove);					//move pac-man		
		_eatPill();									//eat a pill
		_eatPowerPill();							//eat a power pill
	}
	
	/**
	 * Updates the states of the ghosts given the moves returned by the controller.
	 *
	 * @param ghostMoves The moves supplied by the ghosts controller
	 */
	public void updateGhosts(EnumMap<GHOST,MOVE> ghostMoves)
	{
		ghostMoves=_completeGhostMoves(ghostMoves);
		
		if(!_reverseGhosts(ghostMoves,false))
			_updateGhosts(ghostMoves);
	}
	
	public void updateGhostsWithoutReverse(EnumMap<GHOST,MOVE> ghostMoves)
	{
		ghostMoves=_completeGhostMoves(ghostMoves);
		_updateGhosts(ghostMoves);
	}
	
	public void updateGhostsWithForcedReverse(EnumMap<GHOST,MOVE> ghostMoves)
	{
		ghostMoves=_completeGhostMoves(ghostMoves);
		_reverseGhosts(ghostMoves,true);
	}
	
	/**
	 * Updates the game once the individual characters have been updated: check if anyone
	 * can eat anyone else. Then update the lair times and check if Ms Pac-Man should be
	 * awarded the extra live. Then update the time and see if the level or game is over.
	 */
	public void updateGame()
	{
		_feast();									//ghosts eat pac-man or vice versa		
		_updateLairTimes();
		_updatePacManExtraLife();

		tiempoTotal++;
		tiempoLvlActual++;
		
		_checkLevelState();							//check if level/game is over
	}
	
	/**
	 * This method is for specific purposes such as searching a tree in a specific manner. It has to be used cautiously as it might
	 * create an unstable game state and may cause the game to crash.
	 * 
	 * @param feast Whether or not to enable feasting
	 * @param updateLairTimes Whether or not to update the lair times
	 * @param updateExtraLife Whether or not to update the extra life
	 * @param updateTotalTime Whether or not to update the total time
	 * @param updateLevelTime Whether or not to update the level time
	 */
	public void updateGame(boolean feast,boolean updateLairTimes,boolean updateExtraLife,boolean updateTotalTime,boolean updateLevelTime)
	{						
		if(feast) 			_feast();				//ghosts eat pac-man or vice versa		
		if(updateLairTimes) _updateLairTimes();
		if(updateExtraLife) _updatePacManExtraLife();

		if(updateTotalTime) tiempoTotal++;
		if(updateLevelTime) tiempoLvlActual++;
		
		_checkLevelState();							//check if level/game is over
	}
	
	/**
	 * _update lair times.
	 */
	private void _updateLairTimes()
	{
		for(Ghost ghost : fantasmas.values())
			if(ghost.lairTime>0)
				if(--ghost.lairTime==0)
					ghost.currentNodeIndex=laberintoActua.initialGhostNodeIndex;
	}
	
	/**
	 * _update pac man extra life.
	 */
	private void _updatePacManExtraLife()
	{
		if(!pacman.hasReceivedExtraLife && score>=EXTRA_LIFE_SCORE)	//award 1 extra life at 10000 points
		{
			pacman.hasReceivedExtraLife=true;
			pacman.numberOfLivesRemaining++;
		}
	}
	
	/**
	 * _update pac man.
	 *
	 * @param move the move
	 */
	private void _updatePacMan(MOVE move)
	{
		pacman.lastMoveMade=_correctPacManDir(move);		
		pacman.currentNodeIndex=pacman.lastMoveMade == MOVE.NEUTRAL ? pacman.currentNodeIndex : 
			laberintoActua.graph[pacman.currentNodeIndex].neighbourhood.get(pacman.lastMoveMade);
	}

	/**
	 * _correct pac man dir.
	 *
	 * @param direction the direction
	 * @return the mOVE
	 */
	private MOVE _correctPacManDir(MOVE direction)
	{
		Node node=laberintoActua.graph[pacman.currentNodeIndex];
		
		//direction is correct, return it
		if(node.neighbourhood.containsKey(direction))
			return direction;
		else
		{
			//try to use previous direction (i.e., continue in the same direction)
			if(node.neighbourhood.containsKey(pacman.lastMoveMade))
				return pacman.lastMoveMade;
			//else stay put
			else
				return MOVE.NEUTRAL;
		}
	}

	/**
	 * _update ghosts.
	 *
	 * @param moves the moves
	 */
	private void _updateGhosts(EnumMap<GHOST,MOVE> moves)
	{
		for(Entry<GHOST,MOVE> entry : moves.entrySet())
		{
			Ghost ghost=fantasmas.get(entry.getKey());

			if(ghost.lairTime==0)
			{
				if(ghost.edibleTime==0 || ghost.edibleTime%GHOST_SPEED_REDUCTION!=0)
				{
					ghost.lastMoveMade=_checkGhostDir(ghost,entry.getValue());					
					moves.put(entry.getKey(), ghost.lastMoveMade);					
					ghost.currentNodeIndex=laberintoActua.graph[ghost.currentNodeIndex].neighbourhood.get(ghost.lastMoveMade);
				}
			}
		}
	}
	
	private EnumMap<GHOST,MOVE> _completeGhostMoves(EnumMap<GHOST,MOVE> moves)
	{
		if(moves==null)
		{
			moves=new EnumMap<GHOST,MOVE>(GHOST.class);
			
			for(GHOST ghostType : GHOST.values())
				moves.put(ghostType,fantasmas.get(ghostType).lastMoveMade);
		}
		
		if(moves.size()<NUM_GHOSTS)
			for(GHOST ghostType : GHOST.values())
				if(!moves.containsKey(ghostType))
					moves.put(ghostType,MOVE.NEUTRAL);
		
		return moves;
	}
	
	/**
	 * _check ghost dir.
	 *
	 * @param ghost the ghost
	 * @param direction the direction
	 * @return the mOVE
	 */
	private MOVE _checkGhostDir(Ghost ghost,MOVE direction)
	{
		//Gets the neighbours of the node with the node that would correspond to reverse removed
		Node node=laberintoActua.graph[ghost.currentNodeIndex];
		
		//The direction is possible and not opposite to the previous direction of that ghost
		if(node.neighbourhood.containsKey(direction) && direction!=ghost.lastMoveMade.opposite())
			return direction;
		else
		{
			if(node.neighbourhood.containsKey(ghost.lastMoveMade))
				return ghost.lastMoveMade;
			else
			{
				MOVE[] moves=node.allPossibleMoves.get(ghost.lastMoveMade);
				return moves[rnd.nextInt(moves.length)];				
			}
		}
	}
			
	/**
	 * _eat pill.
	 */
	private void _eatPill()
	{
		pastillaFueComida=false;
		
		int pillIndex=laberintoActua.graph[pacman.currentNodeIndex].pillIndex;

		if(pillIndex>=0 && pills.get(pillIndex))
		{
			score+=PILL;
			pills.clear(pillIndex);
			pastillaFueComida=true;
		}
	}
	
	/**
	 * _eat power pill.
	 */
	private void _eatPowerPill()
	{
		pildoraPoderFueComida=false;	
		
		int powerPillIndex=laberintoActua.graph[pacman.currentNodeIndex].powerPillIndex;
		
		if(powerPillIndex>=0 && powerPills.get(powerPillIndex))
		{
			score+=POWER_PILL;
			fastamasComerMultiplicador=1;
			powerPills.clear(powerPillIndex);
			
			int newEdibleTime=(int)(EDIBLE_TIME*(Math.pow(EDIBLE_TIME_REDUCTION,cuentaElLvl%LEVEL_RESET_REDUCTION)));
			
			for(Ghost ghost : fantasmas.values())
				if(ghost.lairTime==0)
					ghost.edibleTime=newEdibleTime;
				else
					ghost.edibleTime=0;
			
			pildoraPoderFueComida=true;
		}
	}
	
	private boolean _reverseGhosts(EnumMap<GHOST,MOVE> moves,boolean force)
	{
		boolean reversed=false;		
		boolean globalReverse=false;
			
		if(Math.random()<GHOST_REVERSAL)
			globalReverse=true;
		
		for(Entry<GHOST,MOVE> entry : moves.entrySet())
		{
			Ghost ghost=fantasmas.get(entry.getKey());
		
			if(tiempoLvlActual>1 && ghost.lairTime==0 && ghost.lastMoveMade!=MOVE.NEUTRAL)
			{
				if(force || (pildoraPoderFueComida || globalReverse))
				{
					ghost.lastMoveMade=ghost.lastMoveMade.opposite();
					ghost.currentNodeIndex=laberintoActua.graph[ghost.currentNodeIndex].neighbourhood.get(ghost.lastMoveMade);
					reversed=true;
					timeOfLastGlobalReversal = tiempoTotal;
				}
			}
		}
		
		return reversed;
	}

	/**
	 * _feast.
	 */
	private void _feast()
	{		
		pacmanFueComido=false;
		
		for(GHOST ghost : GHOST.values())
			fantasmaComido.put(ghost,false);
		
		for(Ghost ghost : fantasmas.values())
		{
			int distance=getShortestPathDistance(pacman.currentNodeIndex, ghost.currentNodeIndex);
			
			if(distance<=EAT_DISTANCE && distance!=-1)
			{
				if(ghost.edibleTime>0)									//pac-man eats ghost
				{
					score+=GHOST_EAT_SCORE*fastamasComerMultiplicador;
					fastamasComerMultiplicador*=2;
					ghost.edibleTime=0;					
					ghost.lairTime=(int)(COMMON_LAIR_TIME*(Math.pow(LAIR_REDUCTION,cuentaElLvl%LEVEL_RESET_REDUCTION)));					
					ghost.currentNodeIndex=laberintoActua.lairNodeIndex;
					ghost.lastMoveMade=MOVE.NEUTRAL;
					
					fantasmaComido.put(ghost.type, true);
				}
				else													//ghost eats pac-man
				{
					pacman.numberOfLivesRemaining--;
					pacmanFueComido=true;
					
					if(pacman.numberOfLivesRemaining<=0)
						juegoTerminado=true;
					else
						_levelReset();
					
					return;
				}
			}
		}
		
		for(Ghost ghost : fantasmas.values())
			if(ghost.edibleTime>0)
				ghost.edibleTime--;
	}
	
	/**
	 * _check level state.
	 */
	private void _checkLevelState()
	{
		//put a cap on the total time a game can be played for
		if(tiempoTotal+1>MAX_TIME)
		{
			juegoTerminado=true;
			score+=pacman.numberOfLivesRemaining*AWARD_LIFE_LEFT;
		}
		//if all pills have been eaten or the time is up...
		else if((pills.isEmpty() && powerPills.isEmpty()) || tiempoLvlActual>=LEVEL_LIMIT)
			_newLevelReset();
	}

	/////////////////////////////////////////////////////////////////////////////
	///////////////////  Query Methods (return only)  ///////////////////////////
	/////////////////////////////////////////////////////////////////////////////

	/**
	 * Returns whether pacman was eaten in the last time step
	 * 
	 * @return whether Ms Pac-Man was eaten.
	 */
	public boolean wasPacManEaten()
	{
		return pacmanFueComido;
	}
	
	/**
	 * Returns whether a ghost was eaten in the last time step
	 * 
	 * @return whether a ghost was eaten.
	 */
	public boolean wasGhostEaten(GHOST ghost)
	{
		return fantasmaComido.get(ghost);
	}
	
	public int getNumGhostsEaten()
	{
		int count=0;
		
		for(GHOST ghost : GHOST.values())
			if(fantasmaComido.get(ghost))
				count++;
		
		return count;
	}
	
	/**
	 * Returns whether a pill was eaten in the last time step
	 * 
	 * @return whether a pill was eaten.
	 */
	public boolean wasPillEaten()
	{
		return pastillaFueComida;
	}
	
	/**
	 * Returns whether a power pill was eaten in the last time step
	 * 
	 * @return whether a power pill was eaten.
	 */
	public boolean wasPowerPillEaten()
	{
		return pildoraPoderFueComida;
	}
		
	/**
	 * Returns the time when the last global reversal event took place.
	 * 
	 * @return time the last global reversal event took place (not including power pill reversals)
	 */
	public int getTimeOfLastGlobalReversal()
	{
		return timeOfLastGlobalReversal;
	}
	
	/**
	 * Checks whether the game is over or not: all lives are lost or 16 levels have been 
	 * played. The variable is set by the methods _feast() and _checkLevelState().
	 *
	 * @return true, if successful
	 */
	public boolean gameOver()
	{
		return juegoTerminado;
	}
	
	/**
	 * Returns the current maze of the game.
	 * 
	 * @return The current maze.
	 */
	public Laberinto getCurrentMaze()
	{
		return laberintoActua;
	}
	
	/**
	 * Returns the x coordinate of the specified node.
	 *
	 * @param nodeIndex the node index
	 * @return the node x cood
	 */
	public int getNodeXCood(int nodeIndex)
	{
		return laberintoActua.graph[nodeIndex].x;
	}
	
	/**
	 * Returns the y coordinate of the specified node.
	 *
	 * @param nodeIndex The node index
	 * @return The node's y coordinate
	 */
	public int getNodeYCood(int nodeIndex)
	{
		return laberintoActua.graph[nodeIndex].y;
	}
	
	/**
	 * Gets the index of the current maze.
	 *
	 * @return The maze index
	 */
	public int getMazeIndex()
	{
		return indiceDeLaberinto;
	}
	
	/**
	 * Returns the current level.
	 *
	 * @return The current level
	 */
	public int getCurrentLevel()
	{
		return cuentaElLvl;
	}
	
	/**
	 * Returns the number of nodes in the current maze.
	 * 
	 * @return number of nodes in the current maze.
	 */
	public int getNumberOfNodes()
	{
		return laberintoActua.graph.length;
	}
	 
	/**
	 * Returns the current value awarded for eating a ghost.
	 * 
	 * @return the current value awarded for eating a ghost.
	 */
	public int getGhostCurrentEdibleScore()
	{
		return GHOST_EAT_SCORE*fastamasComerMultiplicador;
	}
	 
	/**
	 * Returns the node index where ghosts start in the maze once leaving
	 * the lair.
	 * 
	 * @return the node index where ghosts start after leaving the lair.
	 */
	public int getGhostInitialNodeIndex()
	{
		return laberintoActua.initialGhostNodeIndex;
	}
	
	/**
	 * Whether the pill specified is still there or has been eaten.
	 *
	 * @param pillIndex The pill index
	 * @return true, if is pill still available
	 */
	public boolean isPillStillAvailable(int pillIndex)
	{
		return pills.get(pillIndex);
	}
	
	/**
	 * Whether the power pill specified is still there or has been eaten.
	 *
	 * @param powerPillIndex The power pill index
	 * @return true, if is power pill still available
	 */
	public boolean isPowerPillStillAvailable(int powerPillIndex)
	{
		return powerPills.get(powerPillIndex);
	}
	
	/**
	 * Returns the pill index of the node specified. This can be -1 if there
	 * is no pill at the specified node.
	 * 
	 * @param nodeIndex The Index of the node.
	 * @return a number corresponding to the pill index (or -1 if node has no pill)
	 */
	public int getPillIndex(int nodeIndex)
	{
		return laberintoActua.graph[nodeIndex].pillIndex;
	}
	
	/**
	 * Returns the power pill index of the node specified. This can be -1 if there
	 * is no power pill at the specified node.
	 * 
	 * @param nodeIndex The Index of the node.
	 * @return a number corresponding to the power pill index (or -1 if node has no pill)
	 */
	public int getPowerPillIndex(int nodeIndex)
	{
		return laberintoActua.graph[nodeIndex].powerPillIndex;		
	}
	
	/**
	 * Returns the array of node indices that are junctions (3 or more neighbours).
	 * 
	 * @return the junction indices
	 */
	public int[] getJunctionIndices()
	{
		return laberintoActua.junctionIndices;		
	}
	
	/**
	 * Returns the indices to all the nodes that have pills.
	 *
	 * @return the pill indices
	 */
	public int[] getPillIndices()
	{
		return laberintoActua.pillIndices;
	}
	
	/**
	 * Returns the indices to all the nodes that have power pills.
	 *
	 * @return the power pill indices
	 */
	public int[] getPowerPillIndices()
	{
		return laberintoActua.powerPillIndices;
	}
	
	/**
	 * Current node index of Ms Pac-Man.
	 *
	 * @return the pacman current node index
	 */
	public int getPacmanCurrentNodeIndex()
	{
		return pacman.currentNodeIndex;
	}
	
	/**
	 * Current node index of Ms Pac-Man.
	 *
	 * @return the pacman last move made
	 */
	public MOVE getPacmanLastMoveMade()
	{
		return pacman.lastMoveMade;
	}
	
	/**
	 * Lives that remain for Ms Pac-Man.
	 *
	 * @return the number of lives remaining
	 */
	public int getPacmanNumberOfLivesRemaining()
	{
		return pacman.numberOfLivesRemaining;
	}
	
	/**
	 * Current node at which the specified ghost resides.
	 *
	 * @param ghostType the ghost type
	 * @return the ghost current node index
	 */
	public int getGhostCurrentNodeIndex(GHOST ghostType)
	{
		return fantasmas.get(ghostType).currentNodeIndex;
	}

	/**
	 * Current direction of the specified ghost.
	 *
	 * @param ghostType the ghost type
	 * @return the ghost last move made
	 */
	public MOVE getGhostLastMoveMade(GHOST ghostType)
	{
		return fantasmas.get(ghostType).lastMoveMade;
	}
	
	/**
	 * Returns the edible time for the specified ghost.
	 *
	 * @param ghostType the ghost type
	 * @return the ghost edible time
	 */
	public int getGhostEdibleTime(GHOST ghostType)
	{
		return fantasmas.get(ghostType).edibleTime;
	}
	
	/**
	 * Simpler check to see if a ghost is edible.
	 *
	 * @param ghostType the ghost type
	 * @return true, if is ghost edible
	 */
	public boolean isGhostEdible(GHOST ghostType)
	{
		return fantasmas.get(ghostType).edibleTime>0;
	}

	/**
	 * Returns the score of the game.
	 *
	 * @return the score
	 */
	public int getScore()
	{
		return score;
	}
	
	/**
	 * Returns the time of the current level (important with respect to LEVEL_LIMIT).
	 *
	 * @return the current level time
	 */
	public int getCurrentLevelTime()
	{
		return tiempoLvlActual;
	}
	
	/**
	 * Total time the game has been played for (at most LEVEL_LIMIT*MAX_LEVELS).
	 *
	 * @return the total time
	 */
	public int getTotalTime()
	{
		return tiempoTotal;
	}
	
	/**
	 * Total number of pills in the mazes[gs.curMaze]
	 *
	 * @return the number of pills
	 */
	public int getNumberOfPills()
	{
		return laberintoActua.pillIndices.length;
	}
	
	/**
	 * Total number of power pills in the mazes[gs.curMaze]
	 *
	 * @return the number of power pills
	 */
	public int getNumberOfPowerPills()
	{
		return laberintoActua.powerPillIndices.length;
	}
	
	/**
	 * Total number of pills in the mazes[gs.curMaze]
	 *
	 * @return the number of active pills
	 */
	public int getNumberOfActivePills()
	{
		return pills.cardinality();
	}
	
	/**
	 * Total number of power pills in the mazes[gs.curMaze]
	 *
	 * @return the number of active power pills
	 */
	public int getNumberOfActivePowerPills()
	{
		return powerPills.cardinality();
	}
	
	/**
	 * Time left that the specified ghost will spend in the lair.
	 *
	 * @param ghostType the ghost type
	 * @return the ghost lair time
	 */
	public int getGhostLairTime(GHOST ghostType)
	{
		return fantasmas.get(ghostType).lairTime;
	}
	
	/**
	 * returns the indices of all active pills in the mazes[gs.curMaze]
	 *
	 * @return the active pills indices
	 */
	public int[] getActivePillsIndices()
	{
		int[] indices=new int[pills.cardinality()];
		
		int index=0;
		
		for(int i=0;i<laberintoActua.pillIndices.length;i++)
			if(pills.get(i))
				indices[index++]=laberintoActua.pillIndices[i];		
			
		return indices;
	}
	
	/**
	 * returns the indices of all active power pills in the mazes[gs.curMaze]
	 *
	 * @return the active power pills indices
	 */
	public int[] getActivePowerPillsIndices()
	{
		int[] indices=new int[powerPills.cardinality()];
		
		int index=0;
		
		for(int i=0;i<laberintoActua.powerPillIndices.length;i++)
			if(powerPills.get(i))
				indices[index++]=laberintoActua.powerPillIndices[i];		
			
		return indices;
	}

	/**
	 * If in lair (getLairTime(-)>0) or if not at junction.
	 *
	 * @param ghostType the ghost type
	 * @return true, if successful
	 */
	public boolean doesGhostRequireAction(GHOST ghostType)
	{
		//inlcude neutral here for the unique case where the ghost just left the lair
		return ((isJunction(fantasmas.get(ghostType).currentNodeIndex) || (fantasmas.get(ghostType).lastMoveMade==MOVE.NEUTRAL) && fantasmas.get(ghostType).currentNodeIndex==laberintoActua.initialGhostNodeIndex) 
				&& (fantasmas.get(ghostType).edibleTime==0 || fantasmas.get(ghostType).edibleTime%GHOST_SPEED_REDUCTION!=0));
	}
	
	/**
	 * Checks if the node specified by the nodeIndex is a junction.
	 *
	 * @param nodeIndex the node index
	 * @return true, if is junction
	 */
	public boolean isJunction(int nodeIndex)
	{
		return laberintoActua.graph[nodeIndex].numNeighbouringNodes>2;
	}
	
	/**
	 * Gets the possible moves from the node index specified.
	 *
	 * @param nodeIndex The current node index
	 * @return The set of possible moves
	 */
	public MOVE[] getPossibleMoves(int nodeIndex)
	{
		return laberintoActua.graph[nodeIndex].allPossibleMoves.get(MOVE.NEUTRAL);
	}
	
	/**
	 * Gets the possible moves except the one that corresponds to the reverse of the move supplied.
	 *
	 * @param nodeIndex The current node index
	 * @param lastModeMade The last mode made (possible moves will exclude the reverse)
	 * @return The set of possible moves
	 */
	public MOVE[] getPossibleMoves(int nodeIndex,MOVE lastModeMade)
	{
		return laberintoActua.graph[nodeIndex].allPossibleMoves.get(lastModeMade);
	}
	
	/**
	 * Gets the neighbouring nodes from the current node index.
	 *
	 * @param nodeIndex The current node index
	 * @return The set of neighbouring nodes
	 */
	public int[] getNeighbouringNodes(int nodeIndex)
	{
		return laberintoActua.graph[nodeIndex].allNeighbouringNodes.get(MOVE.NEUTRAL);
	}
	
	/**
	 * Gets the neighbouring nodes from the current node index excluding the node 
	 * that corresponds to the opposite of the last move made which is given as an argument.
	 *
	 * @param nodeIndex The current node index
	 * @param lastModeMade The last mode made
	 * @return The set of neighbouring nodes except the one that is opposite of the last move made
	 */
	public int[] getNeighbouringNodes(int nodeIndex,MOVE lastModeMade)
	{
		return laberintoActua.graph[nodeIndex].allNeighbouringNodes.get(lastModeMade);
	}
	
   /**
    * Given a node index and a move to be made, it returns the node index the move takes one to.
    * If there is no neighbour in that direction, the method returns -1.
    * 
    * @param nodeIndex The current node index
	* @param moveToBeMade The move to be made
	* @return The node index of the node the move takes one to
    */
    public int getNeighbour(int nodeIndex, MOVE moveToBeMade)
    {
    	Integer neighbour=laberintoActua.graph[nodeIndex].neighbourhood.get(moveToBeMade);
    	
    	return neighbour==null ? -1 : neighbour;
    }
    	
	/**
	 * Method that returns the direction to take given a node index and an index of a neighbouring
	 * node. Returns null if the neighbour is invalid.
	 *
	 * @param currentNodeIndex The current node index.
	 * @param neighbourNodeIndex The direct neighbour (node index) of the current node.
	 * @return the move to make to reach direct neighbour
	 */
	public MOVE getMoveToMakeToReachDirectNeighbour(int currentNodeIndex,int neighbourNodeIndex)
	{
		for(MOVE move : MOVE.values())
		{
			if(laberintoActua.graph[currentNodeIndex].neighbourhood.containsKey(move) 
					&& laberintoActua.graph[currentNodeIndex].neighbourhood.get(move)==neighbourNodeIndex)
			{
				return move;
			}
		}
		
		return null;
	}
	
	/////////////////////////////////////////////////////////////////////////////
	///////////////////  Helper Methods (computational)  ////////////////////////
	/////////////////////////////////////////////////////////////////////////////

	/**
	 * Returns the PATH distance from any node to any other node.
	 *
	 * @param fromNodeIndex the from node index
	 * @param toNodeIndex the to node index
	 * @return the shortest path distance
	 */
	public int getShortestPathDistance(int fromNodeIndex,int toNodeIndex)
	{
		if(fromNodeIndex==toNodeIndex)
			return 0;		
		else if(fromNodeIndex<toNodeIndex)
			return laberintoActua.shortestPathDistances[((toNodeIndex*(toNodeIndex+1))/2)+fromNodeIndex];
		else
			return laberintoActua.shortestPathDistances[((fromNodeIndex*(fromNodeIndex+1))/2)+toNodeIndex];
	}
	
	/**
	 * Returns the EUCLEDIAN distance between two nodes in the current mazes[gs.curMaze].
	 *
	 * @param fromNodeIndex the from node index
	 * @param toNodeIndex the to node index
	 * @return the euclidean distance
	 */
	public double getEuclideanDistance(int fromNodeIndex,int toNodeIndex)
	{
		return Math.sqrt(Math.pow(laberintoActua.graph[fromNodeIndex].x-laberintoActua.graph[toNodeIndex].x,2)+Math.pow(laberintoActua.graph[fromNodeIndex].y-laberintoActua.graph[toNodeIndex].y,2));
	}
	
	/**
	 * Returns the MANHATTAN distance between two nodes in the current mazes[gs.curMaze].
	 *
	 * @param fromNodeIndex the from node index
	 * @param toNodeIndex the to node index
	 * @return the manhattan distance
	 */
	public int getManhattanDistance(int fromNodeIndex,int toNodeIndex)
	{
		return (int)(Math.abs(laberintoActua.graph[fromNodeIndex].x-laberintoActua.graph[toNodeIndex].x)+Math.abs(laberintoActua.graph[fromNodeIndex].y-laberintoActua.graph[toNodeIndex].y));
	}
	
	/**
	 * Gets the distance.
	 *
	 * @param fromNodeIndex the from node index
	 * @param toNodeIndex the to node index
	 * @param distanceMeasure the distance measure
	 * @return the distance
	 */
	public double getDistance(int fromNodeIndex,int toNodeIndex,DM distanceMeasure)
	{
		switch(distanceMeasure)
		{
			case PATH: return getShortestPathDistance(fromNodeIndex,toNodeIndex);
			case EUCLID: return getEuclideanDistance(fromNodeIndex,toNodeIndex);
			case MANHATTAN: return getManhattanDistance(fromNodeIndex,toNodeIndex);
		}
		
		return -1;
	}
	
	/**
	 * Returns the distance between two nodes taking reversals into account.
	 *
	 * @param fromNodeIndex the index of the originating node
	 * @param toNodeIndex the index of the target node
	 * @param lastMoveMade the last move made
	 * @param distanceMeasure the distance measure to be used
	 * @return the distance between two nodes.
	 */
	public double getDistance(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade,DM distanceMeasure)
	{
		switch(distanceMeasure)
		{
			case PATH: return getApproximateShortestPathDistance(fromNodeIndex,toNodeIndex,lastMoveMade);
			case EUCLID: return getEuclideanDistance(fromNodeIndex,toNodeIndex);
			case MANHATTAN: return getManhattanDistance(fromNodeIndex,toNodeIndex);
		}
		
		return -1;
	}
	
	/**
	 * Gets the closest node index from node index.
	 *
	 * @param fromNodeIndex the from node index
	 * @param targetNodeIndices the target node indices
	 * @param distanceMeasure the distance measure
	 * @return the closest node index from node index
	 */
	public int getClosestNodeIndexFromNodeIndex(int fromNodeIndex,int[] targetNodeIndices,DM distanceMeasure)
	{
		double minDistance=Integer.MAX_VALUE;
		int target=-1;
		
		for(int i=0;i<targetNodeIndices.length;i++)
		{				
			double distance=0;
			
			distance=getDistance(targetNodeIndices[i],fromNodeIndex,distanceMeasure);
					
			if(distance<minDistance)
			{
				minDistance=distance;
				target=targetNodeIndices[i];	
			}
		}
		
		return target;
	}

	/**
	 * Gets the farthest node index from node index.
	 *
	 * @param fromNodeIndex the from node index
	 * @param targetNodeIndices the target node indices
	 * @param distanceMeasure the distance measure
	 * @return the farthest node index from node index
	 */
	public int getFarthestNodeIndexFromNodeIndex(int fromNodeIndex,int[] targetNodeIndices,DM distanceMeasure)
	{
		double maxDistance=Integer.MIN_VALUE;
		int target=-1;
		
		for(int i=0;i<targetNodeIndices.length;i++)
		{				
			double distance=0;
			
			distance=getDistance(targetNodeIndices[i],fromNodeIndex,distanceMeasure);
					
			if(distance>maxDistance)
			{
				maxDistance=distance;
				target=targetNodeIndices[i];	
			}
		}
		
		return target;
	}

	/**
	 * Gets the next move towards target.
	 *
	 * @param fromNodeIndex the from node index
	 * @param toNodeIndex the to node index
	 * @param distanceMeasure the distance measure
	 * @return the next move towards target
	 */
	public MOVE getNextMoveTowardsTarget(int fromNodeIndex,int toNodeIndex,DM distanceMeasure)
	{
		MOVE move=null;

		double minDistance=Integer.MAX_VALUE;

		for(Entry<MOVE,Integer> entry : laberintoActua.graph[fromNodeIndex].neighbourhood.entrySet())
		{
			double distance=getDistance(entry.getValue(),toNodeIndex,distanceMeasure);
								
			if(distance<minDistance)
			{
				minDistance=distance;
				move=entry.getKey();	
			}
		}
		
		return move;
	}
	
	/**
	 * Gets the next move away from target.
	 *
	 * @param fromNodeIndex the from node index
	 * @param toNodeIndex the to node index
	 * @param distanceMeasure the distance measure
	 * @return the next move away from target
	 */
	public MOVE getNextMoveAwayFromTarget(int fromNodeIndex,int toNodeIndex,DM distanceMeasure)
	{
		MOVE move=null;

		double maxDistance=Integer.MIN_VALUE;

		for(Entry<MOVE,Integer> entry : laberintoActua.graph[fromNodeIndex].neighbourhood.entrySet())
		{
			double distance=getDistance(entry.getValue(),toNodeIndex,distanceMeasure);
								
			if(distance>maxDistance)
			{
				maxDistance=distance;
				move=entry.getKey();	
			}
		}
		
		return move;
	}
	
	/**
	 * Gets the approximate next move towards target not considering directions opposing the last move made.
	 *
	 * @param fromNodeIndex The node index from which to move (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @param lastMoveMade The last move made
	 * @param distanceMeasure The distance measure required (Manhattan, Euclidean or Straight line)
	 * @return The approximate next move towards target (chosen greedily)
	 */
	public MOVE getApproximateNextMoveTowardsTarget(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade, DM distanceMeasure)
	{
		MOVE move=null;

		double minDistance=Integer.MAX_VALUE;

		for(Entry<MOVE,Integer> entry : laberintoActua.graph[fromNodeIndex].allNeighbourhoods.get(lastMoveMade).entrySet())
		{
			double distance=getDistance(entry.getValue(),toNodeIndex,distanceMeasure);
								
			if(distance<minDistance)
			{
				minDistance=distance;
				move=entry.getKey();	
			}
		}
		
		return move;
	}
	
	/**
	 * Gets the approximate next move away from a target not considering directions opposing the last move made.
	 *
	 * @param fromNodeIndex The node index from which to move (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @param lastMoveMade The last move made
	 * @param distanceMeasure The distance measure required (Manhattan, Euclidean or Straight line)
	 * @return The approximate next move towards target (chosen greedily)
	 */
	public MOVE getApproximateNextMoveAwayFromTarget(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade, DM distanceMeasure)
	{
		MOVE move=null;

		double maxDistance=Integer.MIN_VALUE;

		for(Entry<MOVE,Integer> entry : laberintoActua.graph[fromNodeIndex].allNeighbourhoods.get(lastMoveMade).entrySet())
		{
			double distance=getDistance(entry.getValue(),toNodeIndex,distanceMeasure);
								
			if(distance>maxDistance)
			{
				maxDistance=distance;
				move=entry.getKey();	
			}
		}
		
		return move;
	}
	
	/**
	 * Gets the exact next move towards target taking into account reversals. This uses the pre-computed paths.
	 *
	 * @param fromNodeIndex The node index from which to move (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @param lastMoveMade The last move made
	 * @param distanceMeasure the distance measure to be used
	 * @return the next move towards target
	 */
	public MOVE getNextMoveTowardsTarget(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade, DM distanceMeasure)
	{
		MOVE move=null;

		double minDistance=Integer.MAX_VALUE;

		for(Entry<MOVE,Integer> entry : laberintoActua.graph[fromNodeIndex].allNeighbourhoods.get(lastMoveMade).entrySet())
		{
			double distance=getDistance(entry.getValue(),toNodeIndex,lastMoveMade,distanceMeasure);
								
			if(distance<minDistance)
			{
				minDistance=distance;
				move=entry.getKey();	
			}
		}
		
		return move;
	}
	
	/**
	 * Gets the exact next move away from target taking into account reversals. This uses the pre-computed paths.
	 *
	 * @param fromNodeIndex The node index from which to move (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @param lastMoveMade The last move made
	 * @param distanceMeasure the distance measure to be used
	 * @return the next move away from target
	 */
	public MOVE getNextMoveAwayFromTarget(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade, DM distanceMeasure)
	{
		MOVE move=null;

		double maxDistance=Integer.MIN_VALUE;

		for(Entry<MOVE,Integer> entry : laberintoActua.graph[fromNodeIndex].allNeighbourhoods.get(lastMoveMade).entrySet())
		{
			double distance=getDistance(entry.getValue(),toNodeIndex,lastMoveMade,distanceMeasure);
								
			if(distance>maxDistance)
			{
				maxDistance=distance;
				move=entry.getKey();	
			}
		}
		
		return move;
	}

	/**
	 * Gets the A* path considering previous moves made (i.e., opposing actions are ignored)
	 *
	 * @param fromNodeIndex The node index from which to move (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @param lastMoveMade The last move made
	 * @return The A* path
	 * 
	 * @deprecated use getShortestPath() instead.
	 */
	@Deprecated
	public int[] getAStarPath(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade)
	{
		return getShortestPath(fromNodeIndex,toNodeIndex,lastMoveMade);
	}
	
	/**
	 * Gets the shortest path from node A to node B as specified by their indices.
	 *
	 * @param fromNodeIndex The node index from where to start (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @return the shortest path from start to target
	 */
	public int[] getShortestPath(int fromNodeIndex,int toNodeIndex)
	{
		return caches[indiceDeLaberinto].getPathFromA2B(fromNodeIndex,toNodeIndex);
	}
	
	/**
	 * Gets the approximate shortest path taking into account the last move made (i.e., no reversals).
	 * This is approximate only as the path is computed greedily. A more accurate path can be obtained
	 * using A* which is slightly more costly.
	 *
	 * @param fromNodeIndex The node index from where to start (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @param lastMoveMade The last move made
	 * @return the shortest path from start to target
	 * 
	 * @deprecated use getShortestPath() instead.
	 */
	@Deprecated
	public int[] getApproximateShortestPath(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade)
	{
		return getShortestPath(fromNodeIndex,toNodeIndex,lastMoveMade);
	}
	
	/**
	 * Gets the shortest path taking into account the last move made (i.e., no reversals).
	 * This is approximate only as the path is computed greedily. A more accurate path can be obtained
	 * using A* which is slightly more costly.
	 *
	 * @param fromNodeIndex The node index from where to start (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @param lastMoveMade The last move made
	 * @return the shortest path from start to target
	 */
	public int[] getShortestPath(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade)
	{
		if(laberintoActua.graph[fromNodeIndex].neighbourhood.size()==0)//lair
			return new int[0];

		return caches[indiceDeLaberinto].getPathFromA2B(fromNodeIndex,toNodeIndex,lastMoveMade);
	}
	
	/**
	 * Similar to getApproximateShortestPath but returns the distance of the path only. It is slightly
	 * more efficient.
	 *  
	 * @param fromNodeIndex The node index from where to start (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @param lastMoveMade The last move made
	 * @return the exact distance of the path
	 * 
	 * @deprecated use getShortestPathDistance() instead.
	 */
	@Deprecated
	public int getApproximateShortestPathDistance(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade)
	{
		return getShortestPathDistance(fromNodeIndex,toNodeIndex,lastMoveMade);
	}
	
	/**
	 * Similar to getShortestPath but returns the distance of the path only. It is slightly
	 * more efficient.
	 *  
	 * @param fromNodeIndex The node index from where to start (i.e., current position)
	 * @param toNodeIndex The target node index
	 * @param lastMoveMade The last move made
	 * @return the exact distance of the path
	 */
	public int getShortestPathDistance(int fromNodeIndex,int toNodeIndex,MOVE lastMoveMade)
	{
		if(laberintoActua.graph[fromNodeIndex].neighbourhood.size()==0)//lair
			return 0;

		return caches[indiceDeLaberinto].getPathDistanceFromA2B(fromNodeIndex,toNodeIndex,lastMoveMade);
	}
}