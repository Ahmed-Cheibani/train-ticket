package cancel.service;

import cancel.async.AsyncTask;
import cancel.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.RestTemplate;
import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CancelServiceImpl implements CancelService{

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private AsyncTask asyncTask;

    private Random random = new Random();

    @Override
    public CancelOrderResult cancelOrderVersion2(CancelOrderInfo info, String loginToken,
                                                 String loginId, HttpHeaders headers) throws Exception{
        CancelOrderResult result;
        String orderId = info.getOrderId();
        AsyncSendToCancelOrderInfo cancelOrderInfo = new AsyncSendToCancelOrderInfo();
        cancelOrderInfo.setLoginToken(loginToken);
        cancelOrderInfo.setOrderId(orderId);
        ChangeOrderResult cancelOrderResult = null;
        ChangeOrderResult cancelOrderOtherResult = null;
        boolean drawBackMoneyResult = false;

        Order orderBegin = getOrderFromBasicInfo(orderId,headers);
        String price = calculateRefund(orderBegin);

        try{
            headers.add("Cookie","jichao=dododo");
            System.out.println("1.异步调用inside-payment-service");
            Future<Boolean> taskDrawBackMoney = asyncTask.drawBackMoneyForOrderCancelDoGet(price,loginId,orderId,loginToken,headers);
            System.out.println("2.异步调用order-other-serivce");
            Future<ChangeOrderResult> taskOrderOtherUpdate = asyncTask.updateOtherOrderStatusToCancelV2DoGet(cancelOrderInfo,headers);
            System.out.println("3.异步调用order-service");
            Future<ChangeOrderResult> taskOrderUpdate = asyncTask.updateOrderStatusToCancelV2DoGet(cancelOrderInfo,headers);
            System.out.println("4.异步调用assurance-service");
            Future<DeleteAssuranceResult> taaskAssurance = asyncTask.cancelAssuranceOrder(orderId,headers);
            System.out.println("5.异步调用food-service");
            Future<CancelFoodOrderResult> taskFood = asyncTask.cancelFoodOrder(orderId,headers);

            while(!taskOrderUpdate.isDone() || !taskOrderOtherUpdate.isDone() || !taskDrawBackMoney.isDone()
                    || !taaskAssurance.isDone() || !taskFood.isDone()) {

            }
            cancelOrderResult = taskOrderUpdate.get();
            cancelOrderOtherResult = taskOrderOtherUpdate.get();
            drawBackMoneyResult = taskDrawBackMoney.get();
        }catch(Exception e){
            e.printStackTrace();
        }
        if((cancelOrderResult.isStatus() ^ cancelOrderOtherResult.isStatus()) && drawBackMoneyResult){
            Order orderFinal = getOrderFromBasicInfo(orderId,headers);
            //检查订单的状态，对的话返回正确，不对的话返回错误
            if(orderFinal.getStatus() != OrderStatus.CANCEL.getCode()){
                CancelOrderResult finalResult = new CancelOrderResult();
                finalResult.setStatus(false);
                finalResult.setMessage("[Error Process Seq]");
                return finalResult;
                //throw new Exception("[Error Process Seq]");
            }else{
                CancelOrderResult finalResult = new CancelOrderResult();
                finalResult.setStatus(true);
                finalResult.setMessage("Success.Processes Seq.");
                return finalResult;
            }
        }else{
            result = new CancelOrderResult();
            result.setStatus(false);
            result.setMessage("Something Wrong");
        }
        return result;
    }

    private VerifyResult verifySsoLogin(String loginToken, @RequestHeader HttpHeaders headers){
        System.out.println("[cancelOrderVersion2][Verify Login] Verifying....");
        HttpEntity requestTokenResult = new HttpEntity(null,headers);
        ResponseEntity<VerifyResult> reTokenResult  = restTemplate.exchange(
                "http://ts-sso-service:12349/verifyLoginToken/" + loginToken,
                HttpMethod.GET,
                requestTokenResult,
                VerifyResult.class);
        VerifyResult tokenResult = reTokenResult.getBody();
        return tokenResult;
    }


    public boolean sendEmail(NotifyInfo notifyInfo, HttpHeaders headers ){
        System.out.println("[Cancel Order Service][Send Email]");
        HttpEntity requestEntity = new HttpEntity(notifyInfo, headers);
        ResponseEntity<Boolean> re = restTemplate.exchange(
                "http://ts-notification-service:17853/notification/order_cancel_success",
                HttpMethod.POST,
                requestEntity,
                Boolean.class);
        boolean result = re.getBody();
//        boolean result = restTemplate.postForObject(
//                "http://ts-notification-service:17853/notification/order_cancel_success",
//                notifyInfo,
//                Boolean.class
//        );
        return result;
    }

    @Override
    public CalculateRefundResult calculateRefund(CancelOrderInfo info, HttpHeaders headers){
        GetOrderByIdInfo getFromOrderInfo = new GetOrderByIdInfo();
        getFromOrderInfo.setOrderId(info.getOrderId());
        GetOrderResult orderResult = getOrderByIdFromOrder(getFromOrderInfo, headers);
        if(orderResult.isStatus() == true){
            Order order = orderResult.getOrder();
            if(order.getStatus() == OrderStatus.NOTPAID.getCode()
                    || order.getStatus() == OrderStatus.PAID.getCode()){
                if(order.getStatus() == OrderStatus.NOTPAID.getCode()){
                    CalculateRefundResult result = new CalculateRefundResult();
                    result.setStatus(true);
                    result.setMessage("Success");
                    result.setRefund("0");
                    System.out.println("[Cancel Order][Refund Price] From Order Service.Not Paid.");
                    return result;
                }else{
                    CalculateRefundResult result = new CalculateRefundResult();
                    result.setStatus(true);
                    result.setMessage("Success");
                    result.setRefund(calculateRefund(order));
                    System.out.println("[Cancel Order][Refund Price] From Order Service.Paid.");
                    return result;
                }
            }else{
                CalculateRefundResult result = new CalculateRefundResult();
                result.setStatus(false);
                result.setMessage("Order Status Cancel Not Permitted");
                result.setRefund("error");
                System.out.println("[Cancel Order][Refund Price] Order. Cancel Not Permitted.");

                return result;
            }
        }else{
            GetOrderByIdInfo getFromOtherOrderInfo = new GetOrderByIdInfo();
            getFromOtherOrderInfo.setOrderId(info.getOrderId());
            GetOrderResult orderOtherResult = getOrderByIdFromOrderOther(getFromOtherOrderInfo, headers);
            if(orderOtherResult.isStatus() == true){
                Order order = orderOtherResult.getOrder();
                if(order.getStatus() == OrderStatus.NOTPAID.getCode()
                        || order.getStatus() == OrderStatus.PAID.getCode()){
                    if(order.getStatus() == OrderStatus.NOTPAID.getCode()){
                        CalculateRefundResult result = new CalculateRefundResult();
                        result.setStatus(true);
                        result.setMessage("Success");
                        result.setRefund("0");
                        System.out.println("[Cancel Order][Refund Price] From Order Other Service.Not Paid.");
                        return result;
                    }else{
                        CalculateRefundResult result = new CalculateRefundResult();
                        result.setStatus(true);
                        result.setMessage("Success");
                        result.setRefund(calculateRefund(order));
                        System.out.println("[Cancel Order][Refund Price] From Order Other Service.Paid.");
                        return result;
                    }
                }else{
                    CalculateRefundResult result = new CalculateRefundResult();
                    result.setStatus(false);
                    result.setMessage("Order Status Cancel Not Permitted");
                    result.setRefund("error");
                    System.out.println("[Cancel Order][Refund Price] Order Other. Cancel Not Permitted.");
                    return result;
                }
            }else{
                CalculateRefundResult result = new CalculateRefundResult();
                result.setStatus(false);
                result.setMessage("Order Not Found");
                result.setRefund("error");
                System.out.println("[Cancel Order][Refund Price] Order not found.");
                return result;
            }
        }
    }

    private String calculateRefund(Order order){
        double totalPrice = Double.parseDouble(order.getPrice());
        double price;
        int c = random.nextInt(6);
        if(c ==  1 || c == 3 || c == 5){
            price = totalPrice * 0.8;
        }else {
            price = totalPrice * 0.7;
        }

        DecimalFormat priceFormat = new java.text.DecimalFormat("0.00");
        String str = priceFormat.format(price);
        System.out.println();
        System.out.println("[Cancel Order]calculate refund - " + str);
        System.out.println();
        return str;
//        if(order.getStatus() == OrderStatus.NOTPAID.getCode()){
//            return "0.00";
//        }
//        System.out.println("[Cancel Order] Order Travel Date:" + order.getTravelDate().toString());
//        Date nowDate = new Date();
//        Calendar cal = Calendar.getInstance();
//        cal.setTime(order.getTravelDate());
//        int year = cal.get(Calendar.YEAR);
//        int month = cal.get(Calendar.MONTH);
//        int day = cal.get(Calendar.DAY_OF_MONTH);
//        Calendar cal2 = Calendar.getInstance();
//        cal2.setTime(order.getTravelTime());
//        int hour = cal2.get(Calendar.HOUR);
//        int minute = cal2.get(Calendar.MINUTE);
//        int second = cal2.get(Calendar.SECOND);
//        Date startTime = new Date(year,
//                month,
//                day,
//                hour,
//                minute,
//                second);
//        System.out.println("[Cancel Order] nowDate  :" + nowDate.toString());
//        System.out.println("[Cancel Order] startTime:" + startTime.toString());
//        if(nowDate.after(startTime)){
//            System.out.println("[Cancel Order] Ticket expire refund 0");
//            return "0";
//        }else{
//            double totalPrice = Double.parseDouble(order.getPrice());
//            double price ;
//
//            int c = counter.incrementAndGet() % 6;
//            if(c ==  1 || c == 2 || c == 3){
//                price = totalPrice * 0.8;
//            }else {
//                price = totalPrice * 0.7;
//            }
//
//            DecimalFormat priceFormat = new java.text.DecimalFormat("0.00");
//            String str = priceFormat.format(price);
//            System.out.println();
//            System.out.println("[Cancel Order]calculate refund - " + str);
//            System.out.println();
//            return str;
//        }
    }

    private ChangeOrderResult cancelFromOrder(ChangeOrderInfo info,  HttpHeaders headers){
        System.out.println("[Cancel Order Service][Change Order Status] Changing....");
        ChangeOrderResult result = restTemplate.postForObject("http://ts-order-service:12031/order/update",info,ChangeOrderResult.class);
        return result;
    }

    private ChangeOrderResult cancelFromOtherOrder(ChangeOrderInfo info, HttpHeaders headers){
        System.out.println("[Cancel Order Service][Change Order Status] Changing....");
        HttpEntity requestEntity = new HttpEntity(info, headers);
        ResponseEntity<ChangeOrderResult> re = restTemplate.exchange(
                "http://ts-order-other-service:12032/orderOther/update",
                HttpMethod.POST,
                requestEntity,
                ChangeOrderResult.class);
        ChangeOrderResult result = re.getBody();
//        ChangeOrderResult result = restTemplate.postForObject("http://ts-order-other-service:12032/orderOther/update",info,ChangeOrderResult.class);
        return result;
    }

    public boolean drawbackMoney(String money,String userId, HttpHeaders headers){
        System.out.println("[Cancel Order Service][Draw Back Money] Draw back money...");
        DrawBackInfo info = new DrawBackInfo();
        info.setMoney(money);
        info.setUserId(userId);
        HttpEntity requestEntity = new HttpEntity(info, headers);
        ResponseEntity<String> re = restTemplate.exchange(
                "http://ts-inside-payment-service:18673/inside_payment/drawBack",
                HttpMethod.POST,
                requestEntity,
               String.class);
        String result = re.getBody();
//        String result = restTemplate.postForObject("http://ts-inside-payment-service:18673/inside_payment/drawBack",info,String.class);
        if(result.equals("true")){
            return true;
        }else{
            return false;
        }
    }

    public GetAccountByIdResult getAccount(GetAccountByIdInfo info, HttpHeaders headers){
        System.out.println("[Cancel Order Service][Get By Id]");
        HttpEntity requestEntity = new HttpEntity(info, headers);
        ResponseEntity<GetAccountByIdResult> re = restTemplate.exchange(
                "http://ts-sso-service:12349/account/findById",
                HttpMethod.POST,
                requestEntity,
                GetAccountByIdResult.class);
        GetAccountByIdResult result = re.getBody();
//        GetAccountByIdResult result = restTemplate.postForObject(
//                "http://ts-sso-service:12349/account/findById",
//                info,
//                GetAccountByIdResult.class
//        );
        return result;
    }

    private Order getOrderFromBasicInfo(String orderId, HttpHeaders headers){

        GetOrderByIdInfo info = new GetOrderByIdInfo(orderId);

        System.out.println("[Cancel Order Service][getOrderFromBasicInfo] Getting....");
        HttpEntity requestEntity = new HttpEntity(info, headers);
        ResponseEntity<GetOrderResult> re = restTemplate.exchange(
                "http://ts-basic-service:15680/basic/getOrderFromMultiSource",
                HttpMethod.POST,
                requestEntity,
                GetOrderResult.class);
        GetOrderResult cor = re.getBody();
        if(cor.isStatus()){
            System.out.println("[=======]getOrderFromBasicInfo OK");
            return cor.getOrder();
        }else{
            return null;
        }
    }

    private GetOrderResult getOrderByIdFromOrder(GetOrderByIdInfo info, HttpHeaders headers){
        System.out.println("[Cancel Order Service][Get Order] Getting....");
        HttpEntity requestEntity = new HttpEntity(info, headers);
        ResponseEntity<GetOrderResult> re = restTemplate.exchange(
                "http://ts-order-service:12031/order/getById/",
                HttpMethod.POST,
                requestEntity,
                GetOrderResult.class);
        GetOrderResult cor = re.getBody();
//        GetOrderResult cor = restTemplate.postForObject(
//                "http://ts-order-service:12031/order/getById/"
//                ,info,GetOrderResult.class);
        return cor;
    }

    private GetOrderResult getOrderByIdFromOrderOther(GetOrderByIdInfo info, HttpHeaders headers){
        System.out.println("[Cancel Order Service][Get Order] Getting....");
        HttpEntity requestEntity = new HttpEntity(info, headers);
        ResponseEntity<GetOrderResult> re = restTemplate.exchange(
                "http://ts-order-other-service:12032/orderOther/getById/",
                HttpMethod.POST,
                requestEntity,
                GetOrderResult.class);
        GetOrderResult cor = re.getBody();
//        GetOrderResult cor = restTemplate.postForObject(
//                "http://ts-order-other-service:12032/orderOther/getById/"
//                ,info,GetOrderResult.class);
        return cor;
    }

}
