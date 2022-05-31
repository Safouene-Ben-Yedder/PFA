package org.framework.recovering;

import org.domain.*;
import org.framework.DynamicVMP;
import org.framework.ObjectivesFunctions;
import org.framework.Parameter;
import org.framework.Utils;
import org.framework.algorithm.stateOfArt.StateOfArtUtils;
import org.framework.reconfigurationAlgorithm.concurrent.StaticReconfMemeCall;
import org.framework.reconfigurationAlgorithm.memeticAlgorithm.MASettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

import static org.domain.VirtualMachine.getById;

public class Cancellation {

    private static Logger logger = DynamicVMP.getLogger();

    private Cancellation() {
        // Default Constructor
    }

    /**
     * State Of Art Manager
     *
     * @param workload                   Workload Trace
     * @param physicalMachines           List of Physical Machines
     * @param virtualMachines            List of Virtual Machines
     * @param derivedVMs                 List of Derived Virtual Machines
     * @param revenueByTime              Revenue by time
     * @param wastedResources            WastedResources by time
     * @param wastedResourcesRatioByTime WastedResourcesRatio per time
     * @param powerByTime                Power Consumption by time
     * @param placements                 List of Placement by time
     * @param code                       Heuristics Algorithm Code
     * @param timeUnit                   Time init
     * @param requestsProcess            Type of Process
     * @param maxPower                   Maximum Power Consumption
     * @param scenarioFile               Name of Scenario
     *                                   <p>
     *                                   b>RequestsProcess</b>:
     *                                   <ul>
     *                                       <li>Requests[0]: requestServed Number of requests served</li>
     *                                       <li>Requests[1]: requestRejected Number of requests rejected</li>
     *                                       <li>Requests[2]: requestUpdated Number of requests updated</li>
     *                                       <li>Requests[3]: violation Number of violation</li>
     *                                   </ul>
     * @throws IOException          Error managing files
     * @throws InterruptedException Multi-thread error
     * @throws ExecutionException   Multi-thread error
     */
    public static void CancellationManager(List<Scenario> workload, List<PhysicalMachine> physicalMachines,
                                           List<VirtualMachine>
                                                   virtualMachines, List<VirtualMachine> derivedVMs,
                                           Map<Integer, Float> revenueByTime, List<Resources> wastedResources, Map<Integer, Float> wastedResourcesRatioByTime,
                                           Map<Integer, Float> powerByTime, Map<Integer, Placement> placements, Integer code, Integer timeUnit,
                                           Integer[] requestsProcess, Float maxPower, String scenarioFile)
            throws IOException, InterruptedException, ExecutionException {

        List<APrioriValue> aPrioriValuesList = new ArrayList<>();
        List<VirtualMachine> vmsToMigrate = new ArrayList<>();
        List<Integer> vmsMigrationEndTimes = new ArrayList<>();
        List<Float> valuesSelectedForecast = new ArrayList<>();

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        MASettings memeConfig = Utils.getMemeConfig(true);
        Callable<Placement> staticReconfgTask;
        Future<Placement> reconfgResult = null;
        Placement reconfgPlacementResult = null;

        Boolean isMigrationActive = false;
        Boolean isUpdateVmUtilization = false;
        Boolean isReconfigurationActive = false;
        Integer actualTimeUnit;
        Integer nextTimeUnit;

        Integer reconfigurationTimeInit = timeUnit + memeConfig.getExecutionInterval();
        Integer reconfigurationTimeEnd = -1;

        Integer migrationTimeInit = -1;
        Integer migrationTimeEnd = -1;

        Integer vmEndTimeMigration = 0;

        for (int iterator = 0; iterator < workload.size(); ++iterator) {
            Scenario request = workload.get(iterator);
            actualTimeUnit = request.getTime();
            //check if is the last request, assign -1 to nextTimeUnit if so.
            nextTimeUnit = iterator + 1 == workload.size() ? -1 : workload.get(iterator + 1).getTime();

            //Check if overloading due to migrations is required
            if (nextTimeUnit != -1 && isMigrationActive && DynamicVMP.isVmBeingMigrated(request.getVirtualMachineID(),
                    request.getCloudServiceID(), vmsToMigrate)) {

                //get the vm that is migrating
                VirtualMachine vmMigrating = getById(request.getVirtualMachineID(), request.getCloudServiceID(),
                        virtualMachines);

                //check the time when the migration of the vm ends
                vmEndTimeMigration = Utils.updateVmEndTimeMigration(vmsToMigrate, vmsMigrationEndTimes,
                        vmEndTimeMigration,
                        vmMigrating);

                //boolean condition to add or not overload to cpu utilization
                isUpdateVmUtilization = actualTimeUnit <= vmEndTimeMigration;
            }

            DynamicVMP.runHeuristics(request, code, physicalMachines, virtualMachines, derivedVMs, requestsProcess,
                    isUpdateVmUtilization);

            // Check if its the last request or a variation of time unit will occurs.
            if (nextTimeUnit == -1 || !actualTimeUnit.equals(nextTimeUnit)) {

                //get the objective functions
                ObjectivesFunctions.getObjectiveFunctionsByTime(physicalMachines,
                        virtualMachines, derivedVMs, wastedResources,
                        wastedResourcesRatioByTime, powerByTime, revenueByTime, timeUnit, actualTimeUnit);
                // get the placement score based on the distance from origin method
                Float placementScore = ObjectivesFunctions.getDistanceOrigenByTime(request.getTime(),
                        maxPower, powerByTime, revenueByTime, wastedResourcesRatioByTime);

                DynamicVMP.updateLeasingCosts(derivedVMs);
//                Utils.checkPathFolders(Constant.PLACEMENT_SCORE_BY_TIME_FILE);
                // Print the Placement Score by Time t
//                Utils.printToFile( Constant.PLACEMENT_SCORE_BY_TIME_FILE + scenarioFile + Constant.EXPERIMENTS_PARAMETERS_TO_OUTPUT_NAME, placementScore);

                timeUnit = actualTimeUnit;

                //create the placement for the actual time unit
                Placement heuristicPlacement = new Placement(PhysicalMachine.clonePMsList(physicalMachines),
                        VirtualMachine.cloneVMsList(virtualMachines),
                        VirtualMachine.cloneVMsList(derivedVMs), placementScore);
                placements.put(actualTimeUnit, heuristicPlacement);

            }
            if (nextTimeUnit != -1 && nextTimeUnit.equals(reconfigurationTimeInit)) {

                // If a new VM request cames while a reconfiguration is in progress, the reconfiguration is canceled.
                if (Parameter.RECOVERING_METHOD.equals(Utils.CANCELLATION) && isMigrationActive && StateOfArtUtils.newVmDuringMemeticExecution(workload, reconfigurationTimeInit, reconfigurationTimeInit +
                        memeConfig.getExecutionDuration())) {
                    reconfigurationTimeInit = reconfigurationTimeInit + memeConfig.getExecutionInterval();

                } else {

                    if (!virtualMachines.isEmpty()) {

                        // Get the list of a priori values
                        aPrioriValuesList = Utils.getAprioriValuesList(actualTimeUnit);

                        // Clone the current placement
                        Placement reconfgPlacement = new Placement(PhysicalMachine.clonePMsList(physicalMachines),
                                VirtualMachine.cloneVMsList(virtualMachines),
                                VirtualMachine.cloneVMsList(derivedVMs));

                        // Config the call for the memetic algorithm
                        staticReconfgTask = new StaticReconfMemeCall(reconfgPlacement, aPrioriValuesList, memeConfig);

                        // Call the memetic algorithm in a separate thread
                        reconfgResult = executorService.submit(staticReconfgTask);

                        // Update the time end of the memetic algorithm execution
                        reconfigurationTimeEnd = reconfigurationTimeInit + memeConfig.getExecutionDuration();
//                            Utils.printToFile(Constant.RECONFIGURATION_CALL_TIMES_FILE,nextTimeUnit);
                    }
                }

            }
        }
    }
}

