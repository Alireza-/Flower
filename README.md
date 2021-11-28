# Flower: A Data Analytics Flow Elasticity Manager
This repository contains the codebase of our prototype of a holistic elasticity management system that exploits advanced optimization
and control theory techniques to manage elasticity of complex data analytics flows on clouds. Flower analyzes statistics and data collected from 
different data-intensive systems to provide the user with a suite of rich functionalities, including: 
* workload dependency analysis
* optimal resource share analysis 
* dynamic resource provisioning
* cross-platform monitoring

The system is presented in our paper ["Flower: A Data Analytics Flow Elasticity Manager"](http://www.vldb.org/pvldb/vol10/p1893-khoshkbarforoushha.pdf) (VLDB 2017).

# How to Install

1. Install Java >= 1.7 Tomcat, Maven
2. mvn clean package
3. Copy Jar file to the Tomcat server. (Follow [tomcat documentation](https://tomcat.apache.org/tomcat-8.0-doc/deployer-howto.html#Deploying_on_a_running_Tomcat_server))

# Contact

If you have a question or feedback, please send us an email:

Ali Khoshkbar, Australian National University, alireza.khoshkbari@gmail.com

Nipun Balan, Newcastle University, nipunbalan@gmail.com 

Prof. Rajiv Ranjan, Newcastle University 

