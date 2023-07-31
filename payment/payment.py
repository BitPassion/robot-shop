import os
import sys
import time
import logging
import uuid
import json
import requests
import opentracing as ot
import opentracing.ext.tags as tags
from flask import Flask
from flask import request
from flask import jsonify
from rabbitmq import Publisher

app = Flask(__name__)

@app.route('/health', methods=['GET'])
def health():
    return 'OK'

@app.route('/pay/<id>', methods=['POST'])
def pay(id):
    app.logger.info('payment for {}'.format(id))
    cart = request.get_json()
    app.logger.info(cart)

    # dummy call to Paypal, hope they dont object
    req = requests.get('https://paypal.com/')
    app.logger.info('paypal returned {}'.format(req.status_code))

    # Generate order id
    orderid = str(uuid.uuid4())
    queueOrder({ 'orderid': orderid, 'user': id, 'cart': cart })

    # add to history
    req = requests.post('http://user:8080/order/' + id,
            data=json.dumps({'orderid': orderid, 'cart': cart}),
            headers={'Content-Type': 'application/json'})
    app.logger.info('order history returned {}'.format(req.status_code))

    # delete cart
    req = requests.delete('http://cart:8080/cart/' + id);
    app.logger.info('cart delete returned {}'.format(req.status_code))

    return jsonify({ 'orderid': orderid })


def queueOrder(order):
    app.logger.info('queue order')
    # RabbitMQ is not currently traced automatically
    # opentracing tracer is automatically set to Instana tracer
    # start a span
    context = ot.tracer.current_context()
    span = ot.tracer.start_span(operation_name='queue-order',
            child_of=ot.tracer.current_context(),
            tags={
                tags.SPAN_KIND: 'producer',
                tags.COMPONENT: 'payment',
                'message_bus.destination': 'orders'
                }
            )

    headers = {}
    ot.tracer.inject(span.context, ot.Format.HTTP_HEADERS, headers)
    app.logger.info('msg headers {}'.format(headers))
    
    publisher.publish(order, headers)

    span.finish()

# RabbitMQ
publisher = Publisher(app.logger)

if __name__ == "__main__":
    sh = logging.StreamHandler(sys.stdout)
    sh.setLevel(logging.INFO)
    app.logger.addHandler(sh)
    app.logger.setLevel(logging.INFO)
    port = int(os.getenv("SHOP_PAYMENT_PORT", "8080"))
    app.run(host='0.0.0.0', port=port)
