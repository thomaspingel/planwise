#include <iostream>
#include <sstream>
#include <string>
#include <math.h>
#include <stdio.h>
#include <sys/stat.h>

#include "gdal_priv.h"
#include "cpl_conv.h"
#include "cpl_string.h"

typedef unsigned char BYTE;

#define DEBUG 1

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

GDALDataset* copyRaster(GDALDataset* sourceRaster, std::string filename, int blockXSize, int blockYSize) {
  const char *pszFormat = "GTiff";
  GDALDriver *poDriver;
  poDriver = GetGDALDriverManager()->GetDriverByName(pszFormat);
  if (poDriver == NULL) {
    std::cerr << "Failed loading driver" << std::endl;
    exit(1);
  }

  char **papszOptions = NULL;
  papszOptions = CSLSetNameValue(papszOptions, "TILED", "YES");
  papszOptions = CSLSetNameValue(papszOptions, "BLOCKXSIZE", numberToString(blockXSize).c_str());
  papszOptions = CSLSetNameValue(papszOptions, "BLOCKYSIZE", numberToString(blockYSize).c_str());

  GDALDataset* dataset = poDriver->CreateCopy(filename.c_str(), sourceRaster, FALSE, papszOptions, NULL, NULL);
  dataset->FlushCache();
  return dataset;
}

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

long calculateUnsatisfiedDemand(std::string targetFilename, std::string demoFilename, std::vector<std::string> facilities, std::vector<float> capacities) {
  GDALDataset* demoDataset = openRaster(demoFilename);
  GDALRasterBand* demoBand = demoDataset->GetRasterBand(1);
  CPLAssert(demoBand->GetRasterDataType() == GDT_Float32);

  int xBlockSize, yBlockSize;
  demoBand->GetBlockSize(&xBlockSize, &yBlockSize);

  GDALDataset* targetDataset = copyRaster(demoDataset, targetFilename, xBlockSize, yBlockSize);
  GDALRasterBand* targetBand = targetDataset->GetRasterBand(1);
  closeRaster(demoDataset);

  targetBand->GetBlockSize(&xBlockSize, &yBlockSize);
  int xSize = targetDataset->GetRasterXSize();
  int ySize = targetDataset->GetRasterYSize();
  int nXBlocks = (xSize + xBlockSize - 1)/xBlockSize;
  int nYBlocks = (ySize + yBlockSize - 1)/yBlockSize;
  int nXValid, nYValid, xOffset, yOffset;

#ifdef DEBUG
  std::cerr << "Target raster properties:" << std::endl;
  std::cerr << " xSize " << xSize << std::endl;
  std::cerr << " ySize " << ySize << std::endl;
  std::cerr << " xBlockSize " << xBlockSize << std::endl;
  std::cerr << " yBlockSize " << yBlockSize << std::endl;
  std::cerr << " nYBlocks " << nYBlocks << std::endl;
  std::cerr << " nXBlocks " << nXBlocks << std::endl;
#endif

  float* buffer = (float*) CPLMalloc(sizeof(float)*xBlockSize*yBlockSize);
  BYTE* facilityBuffer = (BYTE*) CPLMalloc(sizeof(BYTE)*xBlockSize*yBlockSize);

  float nodata = targetBand->GetNoDataValue();

  for (int iFacility = 0; iFacility < (int)facilities.size(); iFacility++) {
#ifdef DEBUG
    std::cerr << "Processing facility " << facilities[iFacility] << std::endl;
#endif
    GDALDataset* facilityDataset = openRaster(facilities[iFacility]);
    GDALRasterBand* facilityBand = facilityDataset->GetRasterBand(1);
    BYTE facilityNodata = facilityBand->GetNoDataValue();

    CPLAssert(facilityBand->GetRasterDataType() == GDT_Byte);
    int xBlockSizeFacility, yBlockSizeFacility;
    facilityBand->GetBlockSize(&xBlockSizeFacility, &yBlockSizeFacility);
    CPLAssert(xBlockSizeFacility == xBlockSize);
    CPLAssert(yBlockSizeFacility == yBlockSize);
    CPLAssert(facilityDataset->GetRasterXSize() == xSize);
    CPLAssert(facilityDataset->GetRasterYSize() == ySize);

    // First pass: we count the still unsatisfied population under the isochrone
    float unsatisfiedCount = 0.0f;
    for (int iXBlock = 0; iXBlock < nXBlocks; ++iXBlock) {
      xOffset = iXBlock*xBlockSize;
      nXValid = xBlockSize;
      if (iXBlock == nXBlocks-1) nXValid = xSize - xOffset;

      for (int iYBlock = 0; iYBlock < nYBlocks; ++iYBlock) {
        yOffset = iYBlock*yBlockSize;
        nYValid = yBlockSize;
        if (iYBlock == nYBlocks-1) nYValid = ySize - yOffset;

        targetBand->ReadBlock(iXBlock, iYBlock, buffer);
        facilityBand->ReadBlock(iXBlock, iYBlock, facilityBuffer);

        for (int iY = 0; iY < nYValid; ++iY) {
          for (int iX = 0; iX < nXValid; ++iX) {
            int iBuff = xBlockSize*iY+iX;
            if (buffer[iBuff] != nodata && facilityBuffer[iBuff] != facilityNodata) {
              unsatisfiedCount += buffer[iBuff];
            }
          }
        }
      }
    }

    float capacity = capacities[iFacility];

    // Each pixel under the isochrone will be multiplied by the proportion of
    // the unsatisfied demand that is satisfied by this facility.
    float factor = 1;
    if (unsatisfiedCount != 0) factor -= (capacity / unsatisfiedCount);
    if (factor < 0) factor = 0;

#ifdef DEBUG
    std::cerr << " Capacity is " << capacity << std::endl;
    std::cerr << " Unsatisfied count is " << unsatisfiedCount << std::endl;
    std::cerr << " Satisfaction factor is " << factor << std::endl;
#endif

    // Second pass: we apply the multiplying factor to each pixel
    for (int iXBlock = 0; iXBlock < nXBlocks; ++iXBlock) {
      xOffset = iXBlock*xBlockSize;
      nXValid = xBlockSize;
      if (iXBlock == nXBlocks-1) nXValid = xSize - xOffset;

      for (int iYBlock = 0; iYBlock < nYBlocks; ++iYBlock) {
        yOffset = iYBlock*yBlockSize;
        nYValid = yBlockSize;
        if (iYBlock == nYBlocks-1) nYValid = ySize - yOffset;

        targetBand->ReadBlock(iXBlock, iYBlock, buffer);
        facilityBand->ReadBlock(iXBlock, iYBlock, facilityBuffer);

        bool blockChanged = false;

        for (int iY = 0; iY < nYValid; ++iY) {
          for (int iX = 0; iX < nXValid; ++iX) {
            int iBuff = xBlockSize*iY+iX;
            if (buffer[iBuff] != nodata && facilityBuffer[iBuff] != facilityNodata) {
              blockChanged = true;
              buffer[iBuff] *= factor;
            }
          }
        }

        if (blockChanged) {
          targetBand->WriteBlock(iXBlock, iYBlock, buffer);
        }
      }
    }

    closeRaster(facilityDataset);
  }

  targetDataset->FlushCache();

  // Finally, make a last pass on the target dataset to count the total
  // unsatisfied population and return it
  float totalUnsatisfied = 0.0;
  for (int iXBlock = 0; iXBlock < nXBlocks; ++iXBlock) {
    xOffset = iXBlock*xBlockSize;
    nXValid = xBlockSize;
    if (iXBlock == nXBlocks-1) nXValid = xSize - xOffset;

    for (int iYBlock = 0; iYBlock < nYBlocks; ++iYBlock) {
      yOffset = iYBlock*yBlockSize;
      nYValid = yBlockSize;
      if (iYBlock == nYBlocks-1) nYValid = ySize - yOffset;

      targetBand->ReadBlock(iXBlock, iYBlock, buffer);

      for (int iY = 0; iY < nYValid; ++iY) {
        for (int iX = 0; iX < nXValid; ++iX) {
          int iBuff = xBlockSize*iY+iX;
          if (buffer[iBuff] != nodata) {
            totalUnsatisfied += buffer[iBuff];
          }
        }
      }
    }
  }

  // Save calculated unsatisifiedDemand as metadata in the dataset file
  targetDataset->SetMetadataItem(DEMAND_METADATA_KEY, numberToString((long)totalUnsatisfied).c_str(), APP_METADATA_DOMAIN);

  CPLFree(buffer);
  CPLFree(facilityBuffer);
  closeRaster(targetDataset);

  return totalUnsatisfied;
}

int main(int argc, char *argv[]) {
  if (argc < 5 || (argc % 2) == 0) {
    std::cerr << "Usage: " << argv[0] << " TARGET.tif POPULATION.tif FACILITYMASK1.tif CAPACITY1 ... FACILITYMASKN.tif CAPACITYN"
      << std::endl << std::endl
      << "Example:" << std::endl
      << " " << argv[0] << "out.tif data/populations/REGIONID.tif \\" << std::endl
      << " data/isochrones/REGIONID/POLYGONID1.tif 500 \\" << std::endl
      << " data/isochrones/REGIONID/POLYGONID2.tif 800" << std::endl << std::endl
      << "Resulting total unsatisfied demand is returned via STDOUT." << std::endl
      << "Note that if the TARGET file exists, it will not be recalculated, though the pre calculated unsatisfied demand will be returned." << std::endl;
    exit(1);
  }

  GDALAllRegister();
  long unsatisifiedDemand = 0;

  if (fileExists(argv[1])) {
#ifdef DEBUG
    std::cerr << "File " << argv[1] << " already exists." << std::endl;
#endif
    unsatisifiedDemand = readUnsatisfiedDemand(argv[1]);
  } else {
    std::vector<std::string> facilities;
    std::vector<float> capacities;
    for (int i = 3; i < argc; i++) {
      facilities.push_back(argv[i]);
      capacities.push_back(atof(argv[++i]));
    }

    unsatisifiedDemand = calculateUnsatisfiedDemand(argv[1], argv[2], facilities, capacities);
  }

  std::cout << unsatisifiedDemand << std::endl;;
}