package com.hisaab.investments.repository;

import com.hisaab.common.model.Enums.AssetType;
import com.hisaab.investments.model.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {
    Optional<Holding> findByAssetTypeAndSymbol(AssetType assetType, String symbol);

    List<Holding> findAllByOrderByAssetTypeAscNameAsc();

    List<Holding> findByAssetType(AssetType assetType);
}
