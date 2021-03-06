package com.wizaord.boursycrypto.gdax.service.gdax;

import com.wizaord.boursycrypto.gdax.config.properties.ApplicationProperties;
import com.wizaord.boursycrypto.gdax.domain.api.Fill;
import com.wizaord.boursycrypto.gdax.domain.api.Order;
import com.wizaord.boursycrypto.gdax.domain.api.PlaceOrder;
import com.wizaord.boursycrypto.gdax.service.notify.SlackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private ApplicationProperties applicationProperties;
    @Autowired
    private SlackService slackService;

    public Optional<List<Order>> loadOrders() {
        LOG.debug("Retrieving orders..");
        final ResponseEntity<Order[]> orders = restTemplate.getForEntity("/orders", Order[].class);
        if (orders.getStatusCode() != HttpStatus.OK) {
            LOG.error("Unable to get the orders");
            return Optional.empty();
        } else {
            return Optional.of(Arrays.asList(orders.getBody())
                    .stream()
                    .filter(o -> o.getProduct_id().equals(this.applicationProperties.getProduct().getName()))
                    .collect(Collectors.toList()));
        }
    }

    public Optional<List<Order>> loadSellOrders() {
        Optional<List<Order>> orders = this.loadOrders();
        if (orders.isPresent()) {
            return Optional.of(orders.get().stream()
                    .filter(o -> o.getSide().equals("sell"))
                    .collect(Collectors.toList()));
        }
        return Optional.empty();
    }

    public Optional<List<Fill>> loadFills() {
        LOG.debug("Retrieving fills..");
        final UriComponents fillUri = UriComponentsBuilder.fromPath("/fills")
                .queryParam("product_id", this.applicationProperties.getProduct().getName())
                .build();
        final ResponseEntity<Fill[]> fills = restTemplate.getForEntity(fillUri.toUriString(), Fill[].class);
        if (fills.getStatusCode() != HttpStatus.OK) {
            LOG.error("Unable to get the orders");
            return Optional.empty();
        } else {
            return Optional.of(Arrays.asList(fills.getBody())
                    .stream()
                    .filter(f -> f.getProduct_id().equals(this.applicationProperties.getProduct().getName()))
                    .collect(Collectors.toList()));
        }
    }

    public Optional<List<Fill>> loadFillsForOrderId(final String orderId) {
        Optional<List<Fill>> fills = this.loadFills();
        if (fills.isPresent()) {
            return Optional.of(fills.get()
                    .stream()
                    .filter(fill -> fill.getOrder_id().equals(orderId))
                    .collect(Collectors.toList()));
        }
        return Optional.empty();
    }

    public Optional<Fill> getLastBuyFill() {
        final Optional<List<Fill>> fills = this.loadFills();
        if (fills.isPresent()) {
            return fills.get().stream()
                    .filter(fill -> fill.getSide().equals("buy"))
                    .sorted((o1, o2) -> (int) ((o1.getTrade_id() - o2.getTrade_id()) * -1))
                    .findFirst();
        }
        return Optional.empty();
    }

    public void cancelOrders() {
        this.loadOrders().orElse(Arrays.asList())
                .stream()
                .forEach(order -> this.cancelOrder(order.getId()));
    }

    public void cancelOrder(final String orderId) {
        LOG.info("Cancel order with ID {}", orderId);
        restTemplate.delete("/orders/" + orderId);
    }

    public Optional<Order> placeLimitSellOrder(final double price, final double nbCoin) {
        NumberFormat nf = new DecimalFormat("#.##");
        final String stringPlacePrice = nf.format(price).replace(",", ".");
        LOG.info("Place a SELL LIMIT ORDER TO {}", price);
        final PlaceOrder placeOrder = PlaceOrder.builder()
                .productId(this.applicationProperties.getProduct().getName())
                .side("sell")
                .type("limit")
                .size(String.valueOf(nbCoin))
                .price(stringPlacePrice)
                .build();

        LOG.info("Positionnement d'un Limit Order en vente a {} pour {}", stringPlacePrice, nbCoin);
        slackService.postCustomMessage("positionnement d un LIMIT SELL ORDER a " + stringPlacePrice + " pour " + nbCoin + " coins");

        final ResponseEntity<Order> placeOrderResponse = restTemplate.postForEntity("/orders", placeOrder, Order.class);
        if (placeOrderResponse.getStatusCode() != HttpStatus.OK) {
            LOG.error("Unable to place the orders : {}", placeOrderResponse.toString());
            return Optional.empty();
        } else {
            return Optional.of(placeOrderResponse.getBody());
        }
    }

    public Optional<Order> placeStopSellOrder(final double priceP, final double nbCoin) {
        NumberFormat nf = new DecimalFormat("#.##");
        final String stringPlacePrice = nf.format(priceP).replace(",", ".");
        LOG.debug("Place a STOP ORDER TO {}", stringPlacePrice);
        final PlaceOrder placeOrder = PlaceOrder.builder()
                .productId(this.applicationProperties.getProduct().getName())
                .size(String.valueOf(nbCoin))
                .price(stringPlacePrice)
                .side("sell")
                .type("market")
                .stop("loss")
                .stopPrice(stringPlacePrice)
                .build();

        LOG.info("Positionnement d'un StopOrder a {} pour {}", stringPlacePrice, nbCoin);
        slackService.postCustomMessage("positionnement d un STOP SELL ORDER a " + stringPlacePrice + " pour " + nbCoin + " coins");

        final ResponseEntity<Order> placeOrderResponse = restTemplate.postForEntity("/orders", placeOrder, Order.class);
        if (placeOrderResponse.getStatusCode() != HttpStatus.OK) {
            LOG.error("Unable to place the orders : {}", placeOrderResponse.toString());
            return Optional.empty();
        } else {
            return Optional.of(placeOrderResponse.getBody());
        }
    }
}
