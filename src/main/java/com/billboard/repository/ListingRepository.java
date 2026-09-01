package com.billboard.repository;

import com.billboard.model.Listing;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link Listing} documents.
 */
public interface ListingRepository extends MongoRepository<Listing, String> {

    /**
     * Used by the webhook handler to find the listing a Moyasar
     * notification refers to.
     */
    Optional<Listing> findByGatewayPaymentId(String gatewayPaymentId);

    /**
     * Used to build the live, ranked billboard (highest bidder first).
     */
    List<Listing> findByStatusOrderByAmountDesc(Listing.Status status);
}
