package com.hisaab.repo;

import com.hisaab.domain.Enums.AssetType;
import com.hisaab.domain.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HoldingRepo extends JpaRepository<Holding, Long> {
    Optional<Holding> findByAssetTypeAndSymbol(AssetType assetType, String symbol);

    List<Holding> findAllByOrderByAssetTypeAscNameAsc();

    List<Holding> findByAssetType(AssetType assetType);
}
