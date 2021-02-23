# amethyst

A scheduler micro-service based on Quartz and Clojure.


## Usage

Amethyst can be used on its own or extended with additional job types.
Out of the box it supports a handful of job types, possibly of limited
use to your average project, but you are free to extend this
collection with the implementation of the org.quartz.Job interface.
See below.


### Run the application locally

`lein ring server`


### Packaging and running as standalone jar

```
lein do clean, ring uberjar
java -jar target/amethyst.jar
```


### Packaging as war

`lein ring uberwar`


## API

The REST API will, by default, answer at `http://localhost:3000/api/...`. 

Amethyst follows quite closely the Quartz library API.  If you are
familiar with it you should be familiar with Amethyst as well.  There
is a Swagger answering at `http://localhost:3000/docs`.  There, you
should get most if not all the info you need to start using the API.


## Extension

On its own, Amethyst doesn't implement a lot of job types.  There are
two ways you can extend it to suit your needs.  First is to fork it
and hack it, but this is probably the least elegant.  The second is to
include it as dependency in your project, write the relevant Job
classes and make them available either in Amethyst's config.edn or
register them with amethyst.scheduler/register-job-type.  At that
point you can use the job type in your REST API calls.  To start the
service, have a look at the amethyst.server/-main or, better, call it
from your main function.


### Examples job class

```clojure
(amethyst.scheduler/defjob Sample1 [{:keys [to subject body]}]
  (log/info "Here we are going to send an email" to subject body)
  (mail/mail to subject body))

(amethyst.scheduler/register-job-type :sample1 "my-namespace.Sample1")
```


```clojure
(amethyst.scheduler/defjob Sample2 [data]
  (println "Second example's data:" data))

(amethyst.scheduler/register-job-type :sample2 "my-namespace.Sample2")
```


## License

Copyright ©  Walter C. Pelissero <walter@pelissero.de>
