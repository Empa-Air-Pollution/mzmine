/*
 * Copyright (c) 2004-2025 The mzmine Development Team
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package io.github.mzmine.modules.io.import_rawdata_tofwerk_h5;

import com.google.common.collect.Range;
import io.github.mzmine.datamodel.MZmineProject;
import io.github.mzmine.datamodel.MassSpectrumType;
import io.github.mzmine.datamodel.PolarityType;
import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.RawDataImportTask;
import io.github.mzmine.datamodel.Scan;
import io.github.mzmine.datamodel.features.SimpleFeatureListAppliedMethod;
import io.github.mzmine.datamodel.impl.SimpleScan;
import io.github.mzmine.modules.MZmineModule;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.parameters.parametertypes.selectors.ScanSelection;
import io.github.mzmine.project.impl.RawDataFileImpl;
import io.github.mzmine.taskcontrol.AbstractTask;
import io.github.mzmine.taskcontrol.TaskStatus;
import io.github.mzmine.util.MemoryMapStorage;
import io.github.mzmine.util.collections.BinarySearch;
import io.github.mzmine.util.collections.IndexRange;
import io.github.mzmine.util.exceptions.ExceptionUtils;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Hashtable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ucar.ma2.Array;
import ucar.ma2.Index;
import ucar.nc2.Attribute;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

/**
 * Imports raw TOF data acquired with TOFWERK instruments and stored in their HDF5 file format.
 * Unlike generic ANDI/netCDF raw data, TOFWERK files store full, uncalibrated TOF spectra under
 * {@code FullSpectra/TofData} together with the polynomial mass calibration in the attributes of
 * the {@code FullSpectra} group.
 */
public class TofwerkH5ImportTask extends AbstractTask implements RawDataImportTask {

  private static final Logger logger = Logger.getLogger(TofwerkH5ImportTask.class.getName());

  private NetcdfFile inputFile;

  private int parsedScans;
  private int validScans = 0;

  private int tofidStart = 0;
  private int tofidEnd;

  private Hashtable<Integer, Double> scansRetentionTimes;

  private final File file;
  private final MZmineProject project;
  private final RawDataFile newMZmineFile;
  private final ParameterSet parameters;
  private final Class<? extends MZmineModule> module;

  private Variable tofdataVariable, timingDataVariable;

  private int massCalMode;
  private final double[] massCalibrationParameters = new double[5];
  private double[] mzValues, mzValuesScans;

  private static final int DIM_TIME0 = 0;
  private static final int DIM_TIME1 = 1;
  private static final int DIM_TOFID = 3;

  public TofwerkH5ImportTask(MZmineProject project, File fileToOpen,
      @NotNull final Class<? extends MZmineModule> module, @NotNull final ParameterSet parameters,
      @NotNull Instant moduleCallDate, @Nullable MemoryMapStorage storage) {
    super(storage, moduleCallDate);
    this.project = project;
    this.file = fileToOpen;
    this.newMZmineFile = new RawDataFileImpl(file.getName(), file.getAbsolutePath(),
        getMemoryMapStorage());
    this.parameters = parameters;
    this.module = module;
  }

  /**
   * @see io.github.mzmine.taskcontrol.Task#getFinishedPercentage()
   */
  @Override
  public double getFinishedPercentage() {
    return validScans == 0 ? 0 : (double) parsedScans / validScans;
  }

  /**
   * @see java.lang.Runnable#run()
   */
  @Override
  public void run() {

    // Update task status
    setStatus(TaskStatus.PROCESSING);
    logger.info("Started parsing file " + file);

    try {

      // Open file
      this.startReading();

      // Get the scan range to read. When this task is created via the unified
      // AllSpectralDataImportModule, `parameters` is an AllSpectralDataImportParameters instance
      // that doesn't carry these fields, so fall back to "no filtering" instead of throwing.
      Range<Integer> scanRangeInt = null;
      Range<Double> retentionTimeRange = null;
      final var scanSelectionParam = parameters.tryGetParameter(
          TofwerkH5ImportParameters.scanSelection);
      if (scanSelectionParam.isPresent()) {
        ScanSelection scanSelection = scanSelectionParam.get().getValue();
        scanRangeInt = scanSelection.getScanNumberRange();
        retentionTimeRange = scanSelection.getScanRTRange();
      }

      // Parse scans
      for (int i = 0; i < validScans; i++) {
        if (isCanceled()) {
          return;
        }
        Double retentionTime = scansRetentionTimes.get(i);
        if (retentionTime == null) {
          throw new IOException("Could not read retention time for scan " + i);
        }
        // Add the scan only if it is in range
        if ((scanRangeInt == null && retentionTimeRange == null) || (scanRangeInt != null
            && scanRangeInt.contains(i)) || (retentionTimeRange != null && retentionTimeRange
            .contains(retentionTime))) {
          newMZmineFile.addScan(this.readScan(i));
        }
        parsedScans++;

      }

      // Close file
      this.finishReading();
      newMZmineFile.getAppliedMethods()
          .add(new SimpleFeatureListAppliedMethod(module, parameters, getModuleCallDate()));
      project.addFile(newMZmineFile);

    } catch (Throwable e) {
      logger.log(Level.SEVERE, "Could not open file " + file.getPath(), e);
      setErrorMessage(ExceptionUtils.exceptionToString(e));
      setStatus(TaskStatus.ERROR);
      return;
    }

    logger.info("Finished parsing " + file + ", parsed " + parsedScans + " scans");

    // Update task status
    setStatus(TaskStatus.FINISHED);

  }

  @Override
  public String getTaskDescription() {
    return "Opening file " + file;
  }

  public void startReading() throws IOException {

    // Open the HDF5 file (netcdf-java reads TOFWERK's HDF5 layout natively)
    try {
      inputFile = NetcdfFile.open(file.getPath());
    } catch (Exception e) {
      logger.severe(e.toString());
      throw (new IOException("Couldn't open input file" + file));
    }

    // Find the full, uncalibrated TOF spectra
    tofdataVariable = inputFile.findVariable("FullSpectra/TofData");
    if (tofdataVariable == null) {
      logger.severe("Could not find variable FullSpectra/TofData");
      throw (new IOException("Could not find variable FullSpectra/TofData"));
    }
    if (tofdataVariable.getRank() != 4) {
      throw new IOException(
          "Unexpected rank for variable FullSpectra/TofData: " + tofdataVariable.getRank());
    }

    // Read the default mass calibration parameters
    Group fullSpectraGroup = inputFile.findGroup("FullSpectra");
    if (fullSpectraGroup == null) {
      logger.severe("Could not find group FullSpectra");
      throw (new IOException("Could not find group FullSpectra"));
    }

    // Get the mode of the default mass calibration
    Attribute massCalModeAttribute = fullSpectraGroup.findAttribute("MassCalibMode");
    if (massCalModeAttribute == null) {
      logger.severe("Could not find attribute MassCalibMode");
      throw (new IOException("Could not find attribute MassCalibMode"));
    }
    massCalMode = massCalModeAttribute.getNumericValue().intValue();

    // Loop over the mass calibration parameters to read them
    for (int i = 0; i < 5; i++) {
      // mode 0 and 1 have only 2 params
      if (massCalMode < 2 && i > 1) {
        break;
      }
      // mode 2 has only 3 params
      if (massCalMode == 2 && i > 2) {
        break;
      }
      // mode 3 has only 4 params
      if (massCalMode == 3 && i > 3) {
        break;
      }

      // Create the attribute name
      String attributeName = "MassCalibration_p" + (i + 1);
      Attribute attributeParam = fullSpectraGroup.findAttribute(attributeName);
      if (attributeParam == null) {
        logger.severe("Could not find attribute " + attributeName);
        throw (new IOException("Could not find attribute " + attributeName));
      }
      massCalibrationParameters[i] = attributeParam.getNumericValue().doubleValue();
    }

    // Generate the mass axis from the calibration parameters
    mzValues = new double[tofdataVariable.getDimension(DIM_TOFID).getLength()];
    switch (massCalMode) {
      case 0 -> {
        for (int i = 0; i < mzValues.length; i++) {
          double intermediate = ((i - massCalibrationParameters[1]) / massCalibrationParameters[0]);
          mzValues[i] = intermediate * intermediate;
        }
      }
      case 1 -> {
        for (int i = 0; i < mzValues.length; i++) {
          double intermediate = (massCalibrationParameters[0] / (i - massCalibrationParameters[1]));
          mzValues[i] = intermediate * intermediate;
        }
      }
      case 2 -> {
        double power = (1 / massCalibrationParameters[2]);
        for (int i = 0; i < mzValues.length; i++) {
          double intermediate = ((i - massCalibrationParameters[1]) / massCalibrationParameters[0]);
          mzValues[i] = Math.pow(intermediate, power);
        }
      }
      default -> throw new IllegalArgumentException(
          "Unexpected/Unimplemented value for mass calibration mode: " + massCalMode);
    }

    // Find the mz range specified by the user and crop the mass axis to it. As above, this
    // parameter isn't present when running through the unified AllSpectralDataImportModule.
    Range<Double> mzRange = parameters.tryGetParameter(TofwerkH5ImportParameters.mzRange)
        .map(p -> p.getValue()).orElse(null);

    if (mzRange != null) {
      IndexRange tofidRange = BinarySearch.indexRange(mzValues, mzRange);
      tofidStart = tofidRange.min();
      tofidEnd = tofidRange.maxExclusive();
      mzValuesScans = new double[tofidEnd - tofidStart];
      System.arraycopy(mzValues, tofidStart, mzValuesScans, 0, mzValuesScans.length);
    } else {
      tofidStart = 0;
      tofidEnd = mzValues.length;
      mzValuesScans = mzValues;
    }

    timingDataVariable = inputFile.findVariable("TimingData/BufTimes");
    if (timingDataVariable == null) {
      logger.severe("Could not find variable TimingData/BufTimes");
      throw (new IOException("Could not find variable TimingData/BufTimes"));
    }
    if (timingDataVariable.getRank() != 2) {
      throw new IOException(
          "Unexpected rank for variable TimingData/BufTimes: " + timingDataVariable.getRank());
    }

    // Read the timing data values (over the 2 timing dimensions)
    int totalScans = timingDataVariable.getDimension(DIM_TIME0).getLength()
        * timingDataVariable.getDimension(DIM_TIME1).getLength();

    Array scanTimeArray;
    try {
      scanTimeArray = timingDataVariable.read();
    } catch (Exception e) {
      logger.severe(e.toString());
      throw (new IOException("Could not read from variable TimingData/BufTimes from file " + file));
    }

    if (scanTimeArray.getSize() != totalScans) {
      logger.severe("Size of array TimingData/BufTimes is not equal to the number of scans");
      throw (new IOException(
          "Size of array TimingData/BufTimes is not equal to the number of scans"));
    }

    // Collect retention times for the (valid) scans. TOFWERK files can end with a run of
    // corrupted timestamps; stop as soon as we see a non-monotonic or implausible jump.
    scansRetentionTimes = new Hashtable<>();
    double retentionTime = -1;
    for (int i = 0; i < totalScans; i++) {
      double newRetentionTime = scanTimeArray.getDouble(i);
      if ((newRetentionTime < retentionTime) || (newRetentionTime - retentionTime > 100000)) {
        break;
      }
      validScans++;
      scansRetentionTimes.put(i, newRetentionTime / 60.0); // convert to minutes
      retentionTime = newRetentionTime;
    }

  }

  public void finishReading() throws IOException {
    inputFile.close();
  }

  /**
   * Reads one scan from the file. Requires that general information has already been read.
   */
  private Scan readScan(int scanNum) throws IOException {

    if (scanNum >= validScans) {
      throw (new IOException("End of file reached"));
    }

    // Get retention time of the scan
    Float retentionTime = scansRetentionTimes.get(scanNum).floatValue();

    // Is there any way how to extract polarity/scan definition from this format?
    PolarityType polarity = PolarityType.UNKNOWN;
    String scanDefinition = "";

    // Get coordinates in the file
    int dim0 = scanNum / timingDataVariable.getDimension(DIM_TIME1).getLength();
    int dim1 = scanNum % timingDataVariable.getDimension(DIM_TIME1).getLength();

    int[] origin = new int[]{dim0, dim1, 0, tofidStart};
    int[] size = new int[]{1, 1, 1, tofidEnd - tofidStart};

    // Read the (mz-range-cropped) intensity values for this scan
    Array intensityValueArray;
    try {
      intensityValueArray = tofdataVariable.read(origin, size);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Could not read from tofdataVariable.", e);
      throw (new IOException("Could not read from tofdataVariable."));
    }

    Index intensityValuesIndex = intensityValueArray.getIndex();
    int arrayLength = intensityValueArray.getShape()[DIM_TOFID];
    double[] intensityValues = new double[arrayLength];

    for (int j = 0; j < arrayLength; j++) {
      intensityValues[j] = intensityValueArray.getDouble(intensityValuesIndex.set(0, 0, 0, j));
    }

    return new SimpleScan(newMZmineFile, scanNum, 1, retentionTime, null, mzValuesScans,
        intensityValues, MassSpectrumType.PROFILE, polarity, scanDefinition, null);

  }

  @Override
  public @NotNull List<RawDataFile> getImportedRawDataFiles() {
    return getStatus() == TaskStatus.FINISHED ? List.of(newMZmineFile) : List.of();
  }
}
