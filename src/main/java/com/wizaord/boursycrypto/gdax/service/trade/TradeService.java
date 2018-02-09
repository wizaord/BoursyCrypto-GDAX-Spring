package com.wizaord.boursycrypto.gdax.service.trade;

import com.wizaord.boursycrypto.gdax.config.properties.ApplicationProperties;
import com.wizaord.boursycrypto.gdax.domain.E_TradingMode;
import com.wizaord.boursycrypto.gdax.domain.E_TradingSellMode;
import com.wizaord.boursycrypto.gdax.domain.api.Fill;
import com.wizaord.boursycrypto.gdax.domain.api.Order;
import com.wizaord.boursycrypto.gdax.domain.feedmessage.Ticker;
import com.wizaord.boursycrypto.gdax.service.AccountService;
import com.wizaord.boursycrypto.gdax.service.OrderService;
import com.wizaord.boursycrypto.gdax.service.SlackService;
import com.wizaord.boursycrypto.gdax.utils.MathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static com.wizaord.boursycrypto.gdax.domain.E_TradingMode.ACHAT;
import static com.wizaord.boursycrypto.gdax.domain.E_TradingMode.VENTE;
import static com.wizaord.boursycrypto.gdax.domain.E_TradingSellMode.BENEFICE;
import static com.wizaord.boursycrypto.gdax.domain.E_TradingSellMode.WAITING_FOR_BENEFICE;
import static com.wizaord.boursycrypto.gdax.utils.MathUtils.df;

@Service
public class TradeService {

  private static final Logger LOG = LoggerFactory.getLogger(TradeService.class);

  @Autowired
  private SlackService slackService;
  @Autowired
  private AccountService accountService;
  @Autowired
  private OrderService orderService;
  @Autowired
  private ApplicationProperties appProp;

  private Double lastCurrentPriceReceived;
  private double currentPrice;
  private E_TradingMode traderMode = E_TradingMode.NOORDER;
  private Order lastBuyOrder;
  private Order stopOrderCurrentOrder;

  public void notifyNewTickerMessage(final Ticker ticMessage) {
    this.lastCurrentPriceReceived = ticMessage.getPrice().doubleValue();
    LOG.debug("New Ticker value {}", this.lastCurrentPriceReceived);
  }

  /**
   * Algo mis en place.
   * Si pas encore de cours sur le prix, on ne fait rien
   * Si on est pas en mode VENTE, on ne fait rien
   * Si on est en mode VENTE
   * - on regarde si un stopOrder est positionné. Si non, on le positionne a XX% en dessous du prix en cours (possible si lors du demarrage de l'application, le cours est tellement bas qu'on ne peut pas mettre le stopOrder)
   * - on calcule la balance et on l'affiche
   * - si on est en deficite, on ne change pas le stop order
   * - si on est en bénéfice, on positionne le stopOrder juste pour gagner de l'argent
   * - et ensuite on fait monter ce stopOrder en fonction de la courbe
   */
  @Scheduled(fixedRateString = "${application.trader.delay}")
  public synchronized void doTrading() {
    // si on a pas de cours, on ne fait rien. Sans prix, on ne peut rien faire
    if (this.lastCurrentPriceReceived == null) {
      LOG.info("en attente d une premiere transaction pour connaitre le cours");
      return;
    }

    // on va travailler avec le currentPrice, on le sauvegarde
    this.currentPrice = this.lastCurrentPriceReceived;

    switch (this.traderMode) {
      case NOORDER:
        LOG.info("MODE UNKNOWN- determination du mode de fonctionnement");
        this.determineTradeMode();
        break;
      case ACHAT:
        LOG.info("MODE ACHAT - cours {}", this.currentPrice);
//        this.doTradingBuyCheck();
        break;
      case VENTE:
        this.logVenteEvolution();
        if (!this.appProp.getTrader().getModeVisualisation()) {
          LOG.debug("MODE VENTE");
          this.doTradingSell();
        }
        break;
    }
  }


  /**
   * Fonction qui permet de determiner dans quel mode de fonctionnement on se trouve
   */
  private void determineTradeMode() {
    // on va verifier si on a pas encore des coins.
    if (this.accountService.getBtc() > 0) {
      LOG.info("CHECK MODE - coin in wallet <{}>. Looking for last buy order", this.accountService.getBtc().doubleValue());
      final Optional<Fill> lastBuyFill = this.orderService.getLastBuyFill();
      if (lastBuyFill.isPresent()) {
        LOG.info("CHECK MODE - Find last order buy. Inject order in this AWESOME project");
        this.notifyNewOrder(lastBuyFill.get().mapToOrder());
        return;
      }
    }
    LOG.info("CHECK MODE - No Btc in wallet. Set en ACHAT MODE");
    this.traderMode = ACHAT;
  }


  /**
   * Fonction qui détermine le mode de vente. Soit en benefice et on suit la courbe. Soit en mode attente
   * On est en mode benefice et donc on suit la courbe qui monte si :
   * - possible si stopOrder n'existe pas              et benefice supérieur à la valeur configurée dans le fichier de configuration
   * - possible si stopOrder inférieur au prix d'achat et benefice supérieur à la valeur configurée dans le fichier de configuration
   * - possible si le prix du stopOrder est supérieur au prix d'achat => deja en mode benefice
   *
   * @returns {E_TRADESELLMODE}
   */
  private E_TradingSellMode determineTradeSellMode() {
    final double coursRequisPourBenefice = MathUtils
            .calculateAddPourcent(this.lastBuyOrder.getPrice().doubleValue(), appProp.getTrader().getVente().getBenefice()
                    .getPourcentBeforeStartVenteMode());
    final double lastOrderPrice = this.lastBuyOrder.getPrice().doubleValue();
    final boolean isStopOrderPlaced = (this.stopOrderCurrentOrder != null);

    if (isStopOrderPlaced) {
      final double stopOrderPrice = this.stopOrderCurrentOrder.getPrice().doubleValue();
      if (stopOrderPrice > lastOrderPrice) {
        // on est dans le cas où on a déjà été en BENEFICE. On y reste
        return BENEFICE;
      }
    }
    // le stop order est posé ou pas. On est en bénéfice uniquement si le cours le permet
    if (this.currentPrice >= coursRequisPourBenefice) {
      return BENEFICE;
    }
    // on a pas engendré assez de bénéfices
    return WAITING_FOR_BENEFICE;
  }

  public void notifyNewOrder(final Order order) {
    LOG.info("NEW ORDER - Receive order {}", order);
    slackService.postCustomMessage("NEW ORDER - Handle order " + order);
    this.accountService.refreshBalance();
    this.lastBuyOrder = order;
    this.traderMode = VENTE;
  }

  public void logVenteEvolution() {
    final double fee = this.lastBuyOrder.getFill_fees().doubleValue();
    final double price = this.lastBuyOrder.getPrice().doubleValue();
    final double evolution = MathUtils.calculatePourcentDifference(this.currentPrice, this.lastBuyOrder.getPrice().doubleValue());

    String message = "COURS EVOL : - achat " + df.format(price) + " - fee " + df.format(fee) + " - now " + this.currentPrice;
    message += " - benefice " + df.format(this.getBalance(this.currentPrice)) + "€ - evolution " + df.format(evolution) + "%";
    LOG.info(message);
  }


  public double getBalance(final double currentPrice) {
    final double lastOrderPrice = this.lastBuyOrder.getPrice().doubleValue();
    final double quantity = this.lastBuyOrder.getSize().doubleValue();
    final double feeAchat = this.lastBuyOrder.getFill_fees().doubleValue();
    final double feeVente = quantity * currentPrice * 0.0025;

    final double prixVente = (quantity * currentPrice) - feeVente;
    final double coutAchat = (quantity * lastOrderPrice) + feeAchat;
    return prixVente - coutAchat;
  }

  /**
   * realisation du trading en mode VENTE
   */
  private void doTradingSell() {
    final boolean isStopOrderPlaced = (this.stopOrderCurrentOrder == null);

    // positionnement du stop order de secours si activé dans le fichier de configuration
    if (this.appProp.getTrader().getVente().getSecureStopOrder().getActivate() && isStopOrderPlaced) {
      final double negativeWaitPourcent = this.appProp.getTrader().getVente().getSecureStopOrder().getPourcent();
      final double stopPrice = MathUtils.calculateRemovePourcent(this.currentPrice, negativeWaitPourcent);
      LOG.info("MODE VENTE - Place a SECURE stop order to {}", df.format(stopPrice));
      this.stopOrderPlace(stopPrice);
      return;
    }

    // on verifie si on est deja en benefice ou non
    //      - possible si stopOrder n'existe pas              et benefice supérieur à la valeur configurée dans le fichier de configuration
    //      - possible si stopOrder inférieur au prix d'achat et benefice supérieur à la valeur configurée dans le fichier de configuration
    //      - possible si le prix du stopOrder est supérieur au prix d'achat => deja en mode benefice
    E_TradingSellMode sellMode = this.determineTradeSellMode();
    switch (sellMode) {
      case WAITING_FOR_BENEFICE:
        final double coursRequisPourBenefice = MathUtils
                .calculateAddPourcent(this.lastBuyOrder.getPrice().doubleValue(), this.appProp.getTrader().getVente().getBenefice()
                        .getPourcentBeforeStartVenteMode());
        LOG.debug("MODE VENTE - Not enougth benef. Waiting benefice to : {}", df.format(coursRequisPourBenefice));
        break;
      case BENEFICE:
        LOG.info("MODE VENTE - Benefice OK");
        this.doTradingSellBenefice();
        break;
    }
  }

  /**
   * Fonction de gestion quand on est en mode vente et BENEFICE
   * Si le stopOrder n'est pas positionné ou inférieur au prix du lastOrderPrice, on le position au seuil minimal
   * Ensuite on fait monter ce stopOrder en fonction du cours
   */
  private void doTradingSellBenefice() {
    final double lastOrderPrice = this.lastBuyOrder.getPrice().doubleValue();
    final double seuilStopPrice = MathUtils
            .calculateAddPourcent(lastOrderPrice, this.appProp.getTrader().getVente().getBenefice().getInitialPourcent());
    final boolean isStopOrderPlaced = (this.stopOrderCurrentOrder != null);

    // test si aucun stop order n'est positionné
    if (!isStopOrderPlaced) {
      // positionnement d'un stop order au prix seuil
      this.stopOrderPlace(seuilStopPrice);
      return;
    }

    // recuperation du seuil du stopOrder
    final double currentStopOrderPrice = this.stopOrderCurrentOrder.getPrice().doubleValue();

    // test si le stop order est le stop de secours
    if (isStopOrderPlaced && currentStopOrderPrice < lastOrderPrice) {
      this.stopOrderPlace(seuilStopPrice);
      return;
    }

    // on est dans les benefices et on a le stop order deja positionne pour assurer notre argent.
    // on fait donc monter le stop en fonction de la hausse de la courbe
    final double newStopOrderPrice = MathUtils
            .calculateRemovePourcent(this.currentPrice, this.appProp.getTrader().getVente().getBenefice().getFollowingPourcent());

    if (newStopOrderPrice <= currentStopOrderPrice) {
      LOG.info("Cours en chute, on ne repositionne pas le stopOrder qui est a {}", df.format(currentStopOrderPrice));
    } else {
      this.stopOrderPlace(newStopOrderPrice);
      return;
    }
  }


  /**
   * Fonction qui positionne un stopOrder a XX% en dessous du court actuel.
   * Le XX% est configurable dans le fichier de configuration
   */
  public void stopOrderPlace(final double price) {
    // si un stop order est deja present, il faut le supprimer
    if (this.stopOrderCurrentOrder != null) {
      this.orderService.cancelOrder(this.stopOrderCurrentOrder.getId());
      this.stopOrderCurrentOrder = null;
    }
    this.orderService.placeStopSellOrder(10, this.accountService.getBtc())
            .ifPresent(order -> this.stopOrderCurrentOrder = order);
  }
}