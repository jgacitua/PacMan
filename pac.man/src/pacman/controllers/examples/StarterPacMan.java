package pacman.controllers.examples;

import java.util.ArrayList;
import pacman.controllers.Controller;
import pacman.game.Game;

import static pacman.game.Constants.*;

/*
� * Controlador de Pac-Man como parte del paquete inicial - simplemente cargar este archivo como una postal llama
� * MyPacMan.zip y se ingresar�n en la clasificaci�n - tan simple como eso! Si�ntase libre de modificar
� * O empezar desde cero, utilizando las clases proporcionadas con el software original. �Mucha suerte!
� *
� * Este controlador utiliza 3 t�cticas, en orden de importancia:
� * 1. Al�jate de cualquier fantasma no comestible que se encuentra en las proximidades
� * 2. Ve tras el fantasma comestibles m�s cercana
� * 3. Ir a la p�ldora p�ldora / potencia m�s cercana
� */
public class StarterPacMan extends Controller<MOVE>
{	
	private static final int MIN_DISTANCE=20;	//si un fantasma es ta cerca, huir
	
	public MOVE getMove(Game game,long timeDue)
	{			
		int current=game.getPacmanCurrentNodeIndex();
		
		//Estrategia 1: si alguno fantasma no comestible est� demasiado cerca (menos de MIN_DISTANCE), huir
		for(GHOST ghost : GHOST.values())
			if(game.getGhostEdibleTime(ghost)==0 && game.getGhostLairTime(ghost)==0)
				if(game.getShortestPathDistance(current,game.getGhostCurrentNodeIndex(ghost))<MIN_DISTANCE)
					return game.getNextMoveAwayFromTarget(game.getPacmanCurrentNodeIndex(),game.getGhostCurrentNodeIndex(ghost),DM.PATH);
		
		//Estrategia 2: encontrar los fantasmas comestibles m�s cercano e ir tras ellos
		int minDistance=Integer.MAX_VALUE;
		GHOST minGhost=null;		
		
		for(GHOST ghost : GHOST.values())
			if(game.getGhostEdibleTime(ghost)>0)
			{
				int distance=game.getShortestPathDistance(current,game.getGhostCurrentNodeIndex(ghost));
				
				if(distance<minDistance)
				{
					minDistance=distance;
					minGhost=ghost;
				}
			}
		
		if(minGhost!=null)	//encontramos un fantasma comestibles
			return game.getNextMoveTowardsTarget(game.getPacmanCurrentNodeIndex(),game.getGhostCurrentNodeIndex(minGhost),DM.PATH);
		
		//Estrategia 3: ir detr�s de las pastillas y p�ldoras de energ�a
		int[] pills=game.getPillIndices();
		int[] powerPills=game.getPowerPillIndices();		
		
		ArrayList<Integer> targets=new ArrayList<Integer>();
		
		for(int i=0;i<pills.length;i++)					//comprobar que las pastillas est�n disponibles			
			if(game.isPillStillAvailable(i))
				targets.add(pills[i]);
		
		for(int i=0;i<powerPills.length;i++)			//consulte con p�ldoras de la energ�a est�n disponibles
			if(game.isPowerPillStillAvailable(i))
				targets.add(powerPills[i]);				
		
		int[] targetsArray=new int[targets.size()];		//convert from ArrayList to array
		
		for(int i=0;i<targetsArray.length;i++)
			targetsArray[i]=targets.get(i);
		
		//return the next direction once the closest target has been identified
		return game.getNextMoveTowardsTarget(current,game.getClosestNodeIndexFromNodeIndex(current,targetsArray,DM.PATH),DM.PATH);
	}
}























