package com.temporal.roleGameDemo.server.workflow;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import com.temporal.roleGameDemo.server.shared.WeatherProvider;
import com.temporal.roleGameDemo.shared.*;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.*;

public class MapNavigationWorkflowImpl implements MapNavigationWorkflow {

    private int mapWidth;
    private int mapHeight;

    private MapCell[][] map;

    private int currPosX;
    private int currPosY;
    private String currWeatherInfo;

    boolean hasFoundTreasure;

    private boolean hasProcessedSignal;
    private boolean isCancelled;
    private boolean isWeatherRequested;

    private final WeatherProvider weatherProvider;
    private final Lumberjack lumberjack;

    public MapNavigationWorkflowImpl()
    {
        weatherProvider = Workflow.newActivityStub(WeatherProvider.class,
                                                   ActivityOptions.newBuilder()
                                                                  .setTaskQueue(TaskQueueNames.ROLE_GAME_TASK_QUEUE)
                                                                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                                                                  .build());

        lumberjack = Workflow.newActivityStub(Lumberjack.class,
                                              ActivityOptions.newBuilder()
                                                             .setTaskQueue(TaskQueueNames.ROLE_GAME_TASK_QUEUE)
                                                             .setStartToCloseTimeout(Duration.ofMinutes(3))
                                                             .build());
    }

    @Override
    public NavigationResults navigateMap(int width, int height)
    {
        System.out.println("DEBUG: Running or re-running workflow: \""
                         + Workflow.getInfo().getWorkflowId() + "\" (run: \""
                         + Workflow.getInfo().getRunId() + "\"; attempt: "
                         + Workflow.getInfo().getAttempt() + ").");

        if (width < 1)
        {
            System.out.println("DEBUG: Exiting with " + NavigationResults.InvalidGameConfiguration + " (width < 1).");
            return NavigationResults.InvalidGameConfiguration;
        }

        if (height < 1)
        {
            System.out.println("DEBUG: Exiting with " + NavigationResults.InvalidGameConfiguration + " (height < 1).");
            return NavigationResults.InvalidGameConfiguration;
        }

        initMap(width, height);
        currPosX = 1;
        currPosY = 1;
        currWeatherInfo = Workflow.sideEffect(String.class, this::getInitialWeatherForecast);
        hasFoundTreasure = false;
        isCancelled = false;

        waitForNextSignal();
        while (true)
        {
            // Act on current position:

            CellKinds currentCell = map[currPosX][currPosY].getKind();

            {
                Instant workflowTimestamp = Instant.ofEpochMilli(Workflow.currentTimeMillis());
                Instant realTimestamp = Instant.ofEpochMilli(System.currentTimeMillis());

                System.out.println("DEBUG: Player position: (" + currPosX + "," + currPosY + ");"
                        + " Cell Kind: " + currentCell + " ('" + map[currPosX][currPosY].getTextCharView() + "');"
                        + " WorkflowTS: " + workflowTimestamp + ";"
                        + " RealTS: " + realTimestamp + ";");
            }

            if (currentCell == CellKinds.Home)
            {
                return hasFoundTreasure
                            ? NavigationResults.HomeWithTreasure
                            : NavigationResults.HomeWithoutTreasure;
            }
            else if (currentCell == CellKinds.Treasure)
            {
                hasFoundTreasure = true;
                map[currPosX][currPosY].setKind(CellKinds.Empty);
            }
            else if (currentCell == CellKinds.Monster)
            {
                System.out.println("DEBUG: Exiting with " + NavigationResults.DeathByMonster + ".");
                return NavigationResults.DeathByMonster;
            }
            else if (currentCell == CellKinds.Empty)
            {
                ; // nothing to do.
            }
            else
            {
                throw new IllegalStateException("The current cell ("
                                              + currPosX
                                              + ", "
                                              + currPosY + ") has an unexpected kind ("
                                              + currentCell
                                              + ").");
            }

            // Act on possible requests received via signals:

            if (isCancelled)
            {
                System.out.println("DEBUG: Exiting with " + NavigationResults.GameAborted + ".");
                return NavigationResults.GameAborted;
            }

            if (isWeatherRequested)
            {
                try
                {
                    currWeatherInfo = weatherProvider.getCurrentRainierForecast();
                }
                catch (Exception ex)
                {
                    currWeatherInfo = ex.getClass().getName() + ": " + ex.getMessage();
                }
            }

            waitForNextSignal();
        }
    }

    @Override
    public void tryMoveUp()
    {
        tryMoveTo(currPosX, currPosY - 1);
    }

    @Override
    public void tryMoveDown()
    {
        tryMoveTo(currPosX, currPosY + 1);
    }

    @Override
    public void tryMoveLeft()
    {
        tryMoveTo(currPosX - 1, currPosY);
    }

    @Override
    public void tryMoveRight()
    {
        tryMoveTo(currPosX + 1, currPosY);
    }

    @Override
    public void quit()
    {
        isCancelled = true;
        hasProcessedSignal = true;
    }

    @Override
    public void checkWeather()
    {
        // Only accept the signal to query the weather if we have no weather info available.
        if (currWeatherInfo == null)
        {
            isWeatherRequested = true;
            hasProcessedSignal = true;
        }
    }

    @Override
    public void plantTrees()
    {
        // Request that the tree growth child starts:

        ChildWorkflowOptions childOptions =
                ChildWorkflowOptions.newBuilder()
                                    .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                                    .build();

        TreeGrowthWorkflow treeWorkflow = Workflow.newChildWorkflowStub(TreeGrowthWorkflow.class, childOptions);
        Async.procedure(treeWorkflow::growTrees, currPosX, currPosY);

        // Wait for child to actually start:

        Promise<WorkflowExecution> treeWorkflowExecution = Workflow.getWorkflowExecution(treeWorkflow);
        treeWorkflowExecution.get();
    }

    @Override
    public void lumberTrees()
    {
        int lumberPosX = currPosX;
        int lumberPosY = currPosY;
        MapCell cell = map[lumberPosX][lumberPosY];

        if (cell.getLumberJob() != null)
        {
            System.out.println("lumberTrees(): Lumber job already in progress at  (" + currPosX + ", " + currPosY + ").");
            return;
        }

        int startTreeCount = cell.getTreeCount();

        System.out.println("InitiatING cutTrees(" + startTreeCount + ") at (" + currPosX + ", " + currPosY + ").");
        Promise<Integer> lumberCompletion = Async.function(lumberjack::cutTrees, startTreeCount);
        cell.setLumberJob(lumberCompletion);
        System.out.println("InitiatED cutTrees(" + startTreeCount + ") at (" + currPosX + ", " + currPosY + ").");

        lumberCompletion.thenApply((lumberCount) ->
            {
                System.out.println("Completed cutTrees(" + startTreeCount + ") at (" + currPosX + ", " + currPosY + ")"
                                +  " with result=" + lumberCount + ".");

                int treesCount = cell.getTreeCount() ;
                treesCount = treesCount - lumberCount;
                treesCount = Math.min(9, Math.max(0, treesCount));
                cell.setTreeCount(treesCount);
                cell.setLumberJob(null);

                return null;
            });

        System.out.println("--");
    }

    @Override
    public void treeHasGrown(int locationX, int locationY)
    {
        if (locationX < 1)
        {
            throw new IllegalArgumentException("locationX may not be < 1.");
        }

        if (locationY < 1)
        {
            throw new IllegalArgumentException("locationY may not be < 1.");
        }

        if (locationX >= mapWidth)
        {
            throw new IllegalArgumentException("locationX may not be >= mapWidth (=" + mapWidth + ").");
        }

        if (locationY >= mapHeight)
        {
            throw new IllegalArgumentException("locationY may not be >= mapHeight (=" + mapHeight + ").");
        }

        int prevTreeCount = map[locationX][locationY].getTreeCount();
        int nextTreeCount = (prevTreeCount < 9) ? prevTreeCount + 1 : prevTreeCount;

        map[locationX][locationY].setTreeCount(nextTreeCount);
    }

    @Override
    public int getMapWidth()
    {
        return mapWidth;
    }

    @Override
    public int getMapHeight()
    {
        return mapHeight;
    }

    @Override
    public View lookAround()
    {
        View view = new View(currPosX, currPosY,
                             map[currPosX-1][currPosY-1], map[currPosX][currPosY-1], map[currPosX+1][currPosY-1],
                             map[currPosX-1][currPosY],   map[currPosX][currPosY],   map[currPosX+1][currPosY],
                             map[currPosX-1][currPosY+1], map[currPosX][currPosY+1], map[currPosX+1][currPosY+1],
                             hasFoundTreasure, currWeatherInfo);

        // System.out.println("lookAround result:\n" + view.toString());
        return view;
    }

    private void tryMoveTo(int targetX, int targetY)
    {
        if (targetX >= 0
                && targetX < mapWidth
                && targetY >= 0
                && targetY < mapHeight
                && map[targetX][targetY].getKind() != CellKinds.Wall)
        {
            currPosX = targetX;
            currPosY = targetY;
            currWeatherInfo = null;
            hasProcessedSignal = true;
        }
    }

    private void waitForNextSignal()
    {
        try
        {
            hasProcessedSignal = false;
            Workflow.await(() -> hasProcessedSignal);
        }
        catch (CanceledFailure cf)
        {
            System.out.println("DEBUG: If we wanted to handle cancellation, it would be here,"
                             + " but for now there is noting to do for such handling.");
            throw cf;
        }
    }

    private String getInitialWeatherForecast()
    {
        try
        {
            return "It's always sunny on " + InetAddress.getLocalHost().getHostName();
        }
        catch (Exception ex)
        {
            return "Initial weather forecast did not work out";
        }
    }

    private void initMap(int width, int height)
    {
        // Create map:
        mapWidth = width + 2;
        mapHeight = height + 2;
        map = new MapCell[mapWidth][mapHeight];

        // Init home cell:
        map[1][1] = new MapCell(CellKinds.Home);

        // Place the treasure:
        Random rnd = Workflow.newRandom();

        int tX = 1, tY = 1;
        while (tX == 1 && tY == 1)
        {
            tX = rnd.nextInt(width);
            tY = rnd.nextInt(height);
        }

        map[tX + 1][tY + 1] = new MapCell(CellKinds.Treasure);

        // Place walls and monsters:

        final double monsterProbability = 0.07;

        for (int y = 0; y < mapHeight; y++)
        {
            for (int x = 0; x < mapWidth; x++)
            {
                if (y == 0)
                {
                    map[x][y] = new MapCell(CellKinds.Wall);
                }
                else if (y == mapHeight - 1)
                {
                    map[x][y] = new MapCell(CellKinds.Wall);
                }
                else if (x == 0)
                {
                    map[x][y] = new MapCell(CellKinds.Wall);
                }
                else if (x == mapWidth - 1)
                {
                    map[x][y] = new MapCell(CellKinds.Wall);
                }
                else if (map[x][y] == null)
                {
                    double dice = rnd.nextDouble();
                    map[x][y] = (dice < monsterProbability)
                                    ? new MapCell(CellKinds.Monster)
                                    : new MapCell(CellKinds.Empty);
                }
            }
        }
    }
}
