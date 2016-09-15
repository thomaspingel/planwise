#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <cmath>
#include <cassert>
#include <stdio.h>
#include <sys/stat.h>

#include "gdal_priv.h"
#include "cpl_conv.h"
#include "cpl_string.h"

#define BENCHMARK 1
#define DEBUG 1

#ifdef BENCHMARK
#include <boost/timer/timer.hpp>
#endif

typedef unsigned char BYTE;

const char* DEMAND_METADATA_KEY = "UNSATISFIED_DEMAND";
const char* APP_METADATA_DOMAIN = "PLANWISE";

// See http://stackoverflow.com/a/13636164/12791
template <typename T> std::string numberToString (T number) {
  std::ostringstream stringStream;
  stringStream << number;
  return stringStream.str();
}

bool fileExists (const std::string& name) {
  struct stat buf;
  return (stat(name.c_str(), &buf) == 0);
}

GDALDataset* openRaster(std::string filename) {
  GDALDataset* poDataset = (GDALDataset*) GDALOpen(filename.c_str(), GA_ReadOnly);
  if (poDataset == NULL) {
    std::cerr << "Failed opening: " << filename << std::endl;
    exit(1);
  }
  return poDataset;
};

void closeRaster(GDALDataset* rasterDataSet) {
  GDALClose(rasterDataSet);
};

long readUnsatisfiedDemand(std::string targetFilename) {
  GDALDataset* targetDataset = openRaster(targetFilename);
  const char* metadataValue = targetDataset->GetMetadataItem(DEMAND_METADATA_KEY, APP_METADATA_DOMAIN);
  if (metadataValue == NULL) {
    std::cerr << "No unsatisfied demand metadata found on " << APP_METADATA_DOMAIN << ":" << DEMAND_METADATA_KEY << std::endl;
    exit(1);
  }
  closeRaster(targetDataset);
  return atol(metadataValue);
}

std::vector<long> calculateUnsatisfiedDemands(std::string demoFilename, std::vector<std::string> facilityMaskFilenames) {

#ifdef BENCHMARK
  boost::timer::auto_cpu_timer t(std::cerr, 6, "Total time elapsed: %t sec CPU, %w sec real\n");
#endif

  int xBlockSize, yBlockSize, targetXSize, targetYSize, targetNXBlocks, targetNYBlocks;
  int nXValid, nYValid, xOffset, yOffset;
  double demoProjection[6];
  double facilityProjection[6];
  float nodata;
  std::string demoProjectionWKT;

  /****************************************************************************
   * Open demographics raster
   ****************************************************************************/
  GDALDataset* demoDataset = openRaster(demoFilename);
  GDALRasterBand* demoBand = demoDataset->GetRasterBand(1);
  assert(demoBand->GetRasterDataType() == GDT_Float32);
  demoBand->GetBlockSize(&xBlockSize, &yBlockSize);
  demoDataset->GetGeoTransform(demoProjection);

  targetXSize = demoDataset->GetRasterXSize();
  targetYSize = demoDataset->GetRasterYSize();
  targetNXBlocks = (targetXSize + xBlockSize - 1)/xBlockSize;
  targetNYBlocks = (targetYSize + yBlockSize - 1)/yBlockSize;
  nodata = demoBand->GetNoDataValue();
  demoProjectionWKT = demoDataset->GetProjectionRef();
  // data = (float*) CPLMalloc(sizeof(float) * (xBlockSize * yBlockSize) * (targetNXBlocks * targetNYBlocks));
  //
  // for (int iYBlock = 0; iYBlock < targetNYBlocks; ++iYBlock) {
  //   for (int iXBlock = 0; iXBlock < targetNXBlocks; ++iXBlock) {
  //     dataOffset = (targetNXBlocks * iYBlock + iXBlock) * (xBlockSize * yBlockSize);
  //     CPLErr err = demoBand->ReadBlock(iXBlock, iYBlock, data + dataOffset);
  //     assert(err == CE_None);
  //   }
  // }



#ifdef DEBUG
  std::cerr << "Target raster properties:" << std::endl;
  std::cerr << " xSize " << targetXSize << std::endl;
  std::cerr << " ySize " << targetYSize << std::endl;
  std::cerr << " xBlockSize " << xBlockSize << std::endl;
  std::cerr << " yBlockSize " << yBlockSize << std::endl;
  std::cerr << " nYBlocks " << targetNYBlocks << std::endl;
  std::cerr << " nXBlocks " << targetNXBlocks << std::endl;
#endif

  /****************************************************************************
   * Iterate over all facilities and count population
   ****************************************************************************/

  BYTE* facilityBuffer = (BYTE*) CPLMalloc(sizeof(BYTE)*xBlockSize*yBlockSize);
  float* buffer = (float*) CPLMalloc(sizeof(float)*xBlockSize*yBlockSize);
  std::vector<long> populations;

  for (size_t iFacility = 0; iFacility < facilityMaskFilenames.size(); iFacility++) {

#ifdef DEBUG
    std::cerr << "Processing facility " << facilityMaskFilenames[iFacility] << std::endl;
#endif

#ifdef BENCHMARK
    boost::timer::auto_cpu_timer t(std::cerr, 6, "Process facility: %t sec CPU, %w sec real\n");
#endif

    // open isochrone dataset
    GDALDataset* facilityDataset = openRaster(facilityMaskFilenames[iFacility]);
    GDALRasterBand* facilityBand = facilityDataset->GetRasterBand(1);
    BYTE facilityNoData = facilityBand->GetNoDataValue();
    assert(facilityBand->GetRasterDataType() == GDT_Byte);
    assert(facilityNoData == 0);

    // set blocks offsets
    facilityDataset->GetGeoTransform(facilityProjection);

    double epsilon = 0.01;
    double facilityMaxY = facilityProjection[3];
    double demoMaxY = demoProjection[3];
    double facilityYRes = facilityProjection[5];
    double demoYRes = demoProjection[5];
    double blocksY = (facilityMaxY - demoMaxY)/(128 * demoYRes);
    int blocksYOffset = round(blocksY);

    double facilityMinX = facilityProjection[0];
    double demoMinX = demoProjection[0];
    double facilityXRes = facilityProjection[1];
    double demoXRes = demoProjection[1];
    double blocksX = (facilityMinX - demoMinX)/(128 * demoXRes);
    int blocksXOffset = round(blocksX);

    int facilityXSize = facilityDataset->GetRasterXSize();
    int facilityYSize = facilityDataset->GetRasterYSize();
    int facilityNXBlocks = (facilityXSize + xBlockSize - 1)/xBlockSize;
    int facilityNYBlocks = (facilityYSize + yBlockSize - 1)/yBlockSize;

#ifdef DEBUG
    std::cerr << " Facility:     " << "maxY=" << facilityMaxY << " minX=" << facilityMinX << std::endl;
    std::cerr << " Demographics: " << "maxY=" << demoMaxY << " minX=" << demoMinX << std::endl;
    std::cerr << " Blocks offsets: " << "X=" << blocksXOffset << " Y=" << blocksYOffset << std::endl;
#endif

    assert(std::abs(facilityYRes - demoYRes) < epsilon);
    assert(facilityMaxY <= (demoMaxY + epsilon));
    assert(std::abs(blocksY - blocksYOffset) < epsilon);
    assert(std::abs(facilityXRes - demoXRes) < epsilon);
    assert(demoMinX <= (facilityMinX + epsilon));
    assert(std::abs(blocksX - blocksXOffset) < epsilon);
    assert(facilityXSize <= (targetXSize - (xBlockSize * blocksXOffset)));
    assert(facilityYSize <= (targetYSize - (yBlockSize * blocksYOffset)));

    /****************************************************************************
     * Count population under the isochrone
     ****************************************************************************/
    double populationCount = 0.0f;
    {
      for (int iYBlock = 0; iYBlock < facilityNYBlocks; ++iYBlock) {
        yOffset = iYBlock*yBlockSize;
        nYValid = yBlockSize;
        if (iYBlock == facilityNYBlocks-1) nYValid = facilityYSize - yOffset;

        for (int iXBlock = 0; iXBlock < facilityNXBlocks; ++iXBlock) {
          xOffset = iXBlock*xBlockSize;
          nXValid = xBlockSize;
          if (iXBlock == facilityNXBlocks-1) nXValid = facilityXSize - xOffset;

          demoBand->ReadBlock(iXBlock+blocksXOffset, iYBlock+blocksYOffset, buffer);
          facilityBand->ReadBlock(iXBlock, iYBlock, facilityBuffer);

          for (int iY = 0; iY < nYValid; ++iY) {
            for (int iX = 0; iX < nXValid; ++iX) {
              int iBuff = xBlockSize*iY+iX;
              if (buffer[iBuff] != nodata && facilityBuffer[iBuff] != facilityNoData) {
                populationCount += buffer[iBuff];
              }
            }
          }
        }
      }
    }

    populations.push_back(populationCount);
    closeRaster(facilityDataset);
  }

  CPLFree(buffer);
  CPLFree(facilityBuffer);
  closeRaster(demoDataset);

  return populations;
}

int main(int argc, char *argv[]) {
  if (argc < 3) {
    std::cerr << "Usage: " << argv[0] << " POPULATION.tif FACILITYMASK1.tif ... FACILITYMASKN.tif" << std::endl;
    exit(1);
  }

  std::vector<std::string> facilities;
  for (int i = 2; i < argc; i++) {
    facilities.push_back(argv[i]);
  }

  GDALAllRegister();

  std::vector<long> unsatisifiedDemands = calculateUnsatisfiedDemands(argv[1], facilities);
  for (int i = 0; i < unsatisifiedDemands.size(); i++) {
    std::cout << unsatisifiedDemands[i] << std::endl;;
  }
}
