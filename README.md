Netty 4 with Kamon
=================================

A simple example of a Netty-Based app that uses Kamon as metrics backend.


### Test


```
docker run -d -p5775:5775/udp -p6831:6831/udp -p6832:6832/udp -p5778:5778 -p16686:16686 -p14268:14268 jaegertracing/all-in-one:latest
```

Then go to: `http://localhost:16686/search`
